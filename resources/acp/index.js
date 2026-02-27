// resources/acp/index.js
const { spawn } = require("node:child_process");
const { Writable, Readable } = require("node:stream");
const acp = require("@agentclientprotocol/sdk");
const { resolvePermissionOutcome } = require("./permission");

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

  const subprocess = spawn(command, args, {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env, ...envPatch }
  });

  const client = {
    async sessionUpdate(params) {
      if (callbacks.onSessionUpdate) {
        callbacks.onSessionUpdate(params);
      }
    },
    async requestPermission(params) {
      if (callbacks.onRequestPermission) {
        callbacks.onRequestPermission(params);
      }
      return resolvePermissionOutcome(params);
    },
    async readTextFile(params) {
      return callbacks.onReadTextFile(params);
    },
    // Hardcoded dry-run: acknowledge write requests without mutating disk.
    async writeTextFile(_params) {
      return {};
    }
  };

  const input = Writable.toWeb(subprocess.stdin);
  const output = Readable.toWeb(subprocess.stdout);
  const stream = acp.ndJsonStream(input, output);
  const connection = new acp.ClientSideConnection(() => client, stream);

  return {
    connection,
    subprocess,
    protocolVersion: acp.PROTOCOL_VERSION
  };
}

module.exports = {
  createConnection,
  __test__: {
    buildNpxAgentPackageConfig
  }
};
