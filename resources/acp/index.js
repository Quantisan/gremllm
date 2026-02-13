// resources/acp/index.js
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
    const toolName = params.toolCall?.title || "";
    const options = params.options || [];
    const isRead = toolName === "Read";

    if (isRead) {
      const approveOption = options[0];
      console.log("[ACP] Auto-approved read permission");
      return {
        outcome: {
          outcome: "selected",
          optionId: approveOption?.optionId ?? 0
        }
      };
    }

    const rejectOption =
      options.find((option) => /reject|deny/i.test(option.label || "")) ||
      options[options.length - 1];
    console.log("[ACP] Rejected non-read permission:", toolName);
    return {
      outcome: {
        outcome: "selected",
        optionId: rejectOption?.optionId ?? 0
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
  if (!connection) await initialize(); // Lazy init
  const result = await connection.newSession({ cwd, mcpServers: [] });
  return result.sessionId;
}

async function resumeSession(cwd, sessionId) {
  if (!connection) await initialize();
  console.log("[acp] resuming session:", sessionId, "cwd:", cwd);
  await connection.unstable_resumeSession({
    sessionId,
    cwd,
    mcpServers: []
  });
  return sessionId;
}

async function prompt(sessionId, contentBlocks) {
  // PITFALL: Unlike newSession, this throws rather than lazy-initializing.
  // If subprocess crashes mid-session, callers must create a new session.
  // Consider adding auto-recovery or lazy init if this becomes a pain point.
  if (!connection) throw new Error("Not initialized");
  const result = await connection.prompt({
    sessionId,
    prompt: contentBlocks
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
  resumeSession,
  prompt,
  shutdown
};
