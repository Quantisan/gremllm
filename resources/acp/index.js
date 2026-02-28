// resources/acp/index.js
const { spawn } = require("node:child_process");
const { Writable, Readable } = require("node:stream");
const acp = require("@agentclientprotocol/sdk");
const { makeResolver } = require("./permission");

const sessionCwdMap = new Map();

function rememberToolName(toolNamesByCallId, params) {
  const update = params?.update;
  const toolCallId = update?.toolCallId;
  const toolName = update?._meta?.claudeCode?.toolName;

  if (typeof toolCallId === "string" && typeof toolName === "string" && toolName.length > 0) {
    toolNamesByCallId.set(toolCallId, toolName);
  }
}

function enrichPermissionParams(toolNamesByCallId, params) {
  const toolCall = params?.toolCall;
  const toolCallId = toolCall?.toolCallId;
  const trackedToolName =
    toolCall?.toolName ??
    toolCall?._meta?.claudeCode?.toolName ??
    (typeof toolCallId === "string" ? toolNamesByCallId.get(toolCallId) : undefined);

  if (typeof trackedToolName !== "string" || trackedToolName.length === 0) {
    return params;
  }

  return {
    ...params,
    toolCall: {
      ...toolCall,
      toolName: trackedToolName
    }
  };
}

function normalizeAgentPackageMode(agentPackageMode) {
  return agentPackageMode === "latest" ? "latest" : "cached";
}

function buildNpxAgentPackageConfig(agentPackageMode) {
  if (normalizeAgentPackageMode(agentPackageMode) === "cached") {
    return {
      command: "npx",
      args: ["@zed-industries/claude-agent-acp"],
      envPatch: {}
    };
  }

  return {
    command: "npx",
    args: [
      "--yes",
      "--package=@zed-industries/claude-agent-acp@latest",
      "--",
      "claude-agent-acp"
    ],
    envPatch: {
      npm_config_prefer_online: "true"
    }
  };
}

function createConnection(options = {}) {
  const callbacks = options;
  const { command, args, envPatch } = buildNpxAgentPackageConfig(options.agentPackageMode);
  const toolNamesByCallId = new Map();

  const subprocess = spawn(command, args, {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env, ...envPatch }
  });

  const resolver = makeResolver((sessionId) => sessionCwdMap.get(sessionId));

  const client = {
    async sessionUpdate(params) {
      rememberToolName(toolNamesByCallId, params);
      if (callbacks.onSessionUpdate) {
        callbacks.onSessionUpdate(params);
      }
    },
    async requestPermission(params) {
      const enrichedParams = enrichPermissionParams(toolNamesByCallId, params);
      if (callbacks.onRequestPermission) {
        callbacks.onRequestPermission(enrichedParams);
      }
      return resolver(enrichedParams);
    },
    async readTextFile(params) {
      return callbacks.onReadTextFile(params);
    },
    // Hardcoded dry-run: acknowledge write requests without mutating disk.
    async writeTextFile(params) {
      if (callbacks.onWriteTextFile) {
        callbacks.onWriteTextFile(params);
      }
      return {};
    }
  };

  const input = Writable.toWeb(subprocess.stdin);
  const output = Readable.toWeb(subprocess.stdout);
  const stream = acp.ndJsonStream(input, output);
  const connection = new acp.ClientSideConnection(() => client, stream);

  const originalNewSession = connection.newSession.bind(connection);
  connection.newSession = async (params) => {
    const result = await originalNewSession(params);
    sessionCwdMap.set(result.sessionId, params.cwd);
    return result;
  };

  const originalResumeSession = connection.unstable_resumeSession.bind(connection);
  connection.unstable_resumeSession = async (params) => {
    const result = await originalResumeSession(params);
    sessionCwdMap.set(params.sessionId, params.cwd);
    return result;
  };

  return {
    connection,
    subprocess,
    protocolVersion: acp.PROTOCOL_VERSION
  };
}

module.exports = {
  createConnection,
  __test__: {
    buildNpxAgentPackageConfig,
    enrichPermissionParams,
    rememberToolName
  }
};
