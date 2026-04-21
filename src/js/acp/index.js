const acp = require("@agentclientprotocol/sdk");
const { ClaudeAcpAgent } = require("@agentclientprotocol/claude-agent-acp/dist/lib.js");
const { makeResolver, requestedToolName } = require("./permission");
const permission = require("./permission");

function rememberToolName(toolNamesByCallId, params) {
  const update = params?.update;
  const toolCallId = update?.toolCallId;
  const toolName = update?._meta?.claudeCode?.toolName;

  // TODO: toolNamesByCallId Map grows unbounded per connection (one entry per tool call)
  if (typeof toolCallId === "string" && typeof toolName === "string" && toolName.length > 0) {
    toolNamesByCallId.set(toolCallId, toolName);
  }
}

function enrichPermissionParams(toolNamesByCallId, params) {
  const toolCall = params?.toolCall;
  const toolCallId = toolCall?.toolCallId;
  const trackedToolName =
    requestedToolName(toolCall) ??
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

function createConnection(options = {}) {
  const callbacks = options;
  const toolNamesByCallId = new Map();

  // The ACP SDK accepts generic streams for ndjson transport (see the pinned
  // local packages node_modules/@agentclientprotocol/sdk/dist/acp.js and
  // node_modules/@agentclientprotocol/sdk/dist/stream.js), so Gremllm hosts
  // claude-agent-acp in-process and bridges client/agent traffic with paired
  // TransformStreams instead of subprocess stdio.
  // TODO: failure propagation is unexamined — if the agent side throws mid-message or the
  // ndjson codec encounters a malformed frame, it's unclear whether the error surfaces to
  // the connection's promise chain or is silently dropped.
  const clientToAgent = new TransformStream();
  const agentToClient = new TransformStream();

  const clientStream = acp.ndJsonStream(clientToAgent.writable, agentToClient.readable);
  const agentStream = acp.ndJsonStream(agentToClient.writable, clientToAgent.readable);

  const sessionCwdMap = new Map();

  // Agent is captured synchronously — AgentSideConnection calls the factory immediately.
  // Load-bearing: disposeAgent below depends on `agent` being set at construction time.
  // TODO: verify this is a guaranteed SDK contract, not an observed implementation detail.
  let agent;
  new acp.AgentSideConnection((client) => {
    agent = new ClaudeAcpAgent(client);
    return agent;
  }, agentStream);
  if (!agent) {
    throw new Error("AgentSideConnection did not invoke factory synchronously");
  }

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
    // Intentional dry-run: acknowledge ACP writes so the agent can complete the
    // tool call successfully and surface a reviewable diff/proposal, but never
    // mutate disk here. Gremllm applies file changes later through its own
    // explicit accept/reject flow. If permission is rejected instead, Claude
    // interprets the step as a user denial and reports the tool call as failed.
    async writeTextFile(params) {
      if (callbacks.onWriteTextFile) {
        callbacks.onWriteTextFile(params);
      }
      return {};
    }
  };

  const connection = new acp.ClientSideConnection(() => client, clientStream);

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

  async function disposeAgent() {
    if (agent) {
      await agent.dispose().catch(() => {});
    }
    clientToAgent.writable.close().catch(() => {});
    agentToClient.writable.close().catch(() => {});
  }

  return {
    connection,
    disposeAgent,
    protocolVersion: acp.PROTOCOL_VERSION
  };
}

module.exports = {
  createConnection,
  __test__: {
    enrichPermissionParams,
    rememberToolName,
    makeResolver,
    permissionRequestedToolName: requestedToolName,
    permissionRequestedPath: permission.__test__.requestedPath
  }
};
