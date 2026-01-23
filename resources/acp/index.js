// resources/acp.js
const { spawn } = require("node:child_process");
const { Writable, Readable } = require("node:stream");
const acp = require("@agentclientprotocol/sdk");

// Module state
let subprocess = null;
let connection = null;
let dispatcher = null;

// Dispatch bridge - CLJS sets this
function setDispatcher(fn) {
  dispatcher = fn;
}

// Client implementation that dispatches to CLJS
const client = {
  async sessionUpdate(params) {
    if (dispatcher) {
      dispatcher("acp.events/session-update", {
        sessionId: params.sessionId,
        update: params.update
      });
    }
  },

  async requestPermission(params) {
    // For Slice 1: auto-approve all
    console.log("[ACP] Permission requested:", params.toolCall?.title);
    const firstOption = params.options?.[0];
    return {
      outcome: {
        outcome: "selected",
        optionId: firstOption?.optionId ?? 0
      }
    };
  }
};

async function initialize() {
  if (connection) return; // Idempotent

  subprocess = spawn("npx", ["@zed-industries/claude-code-acp"], {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env }
  });

  const input = Writable.toWeb(subprocess.stdin);
  const output = Readable.toWeb(subprocess.stdout);
  const stream = acp.ndJsonStream(input, output);

  connection = new acp.ClientSideConnection(() => client, stream);

  await connection.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: { fs: {}, terminal: false },
    clientInfo: {
      name: "gremllm",
      title: "Gremllm",
      version: "0.1.0"
    }
  });
}

async function newSession(cwd) {
  if (!connection) throw new Error("Not initialized");
  const result = await connection.newSession({ cwd, mcpServers: [] });
  return result.sessionId;
}

async function prompt(sessionId, text) {
  if (!connection) throw new Error("Not initialized");
  const result = await connection.prompt({
    sessionId,
    prompt: [{ type: "text", text }]
  });
  return result;
}

function shutdown() {
  if (subprocess) {
    subprocess.kill("SIGTERM");
    subprocess = null;
    connection = null;
  }
}

module.exports = {
  setDispatcher,
  initialize,
  newSession,
  prompt,
  shutdown
};
