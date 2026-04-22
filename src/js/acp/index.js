const acp = require("@agentclientprotocol/sdk");
const { ClaudeAcpAgent } = require("@agentclientprotocol/claude-agent-acp/dist/lib.js");

function createConnection(options = {}) {
  const callbacks = options;

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

  // sessionCwdMap is genuinely connection-local state: cwd is captured at session
  // creation time and passed to resolvePermission so the policy can verify paths.
  const sessionCwdMap = new Map();

  const agentReady = new Promise((resolve) => {
    new acp.AgentSideConnection((client) => {
      const agent = new ClaudeAcpAgent(client);
      resolve(agent);
      return agent;
    }, agentStream);
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
      return callbacks.resolvePermission(params, sessionCwdMap.get(params.sessionId));
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
    const agent = await agentReady;
    await agent.dispose().catch((err) => console.error("[ACP] agent dispose failed", err));
    clientToAgent.writable.close().catch(() => {});
    agentToClient.writable.close().catch(() => {});
  }

  return {
    connection,
    disposeAgent,
    protocolVersion: acp.PROTOCOL_VERSION
  };
}

module.exports = { createConnection };
