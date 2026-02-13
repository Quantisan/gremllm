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

function selectOptionByKind(options, preferredKinds, fallback) {
  for (const kind of preferredKinds) {
    const match = options.find((option) => option?.kind === kind);
    if (match) return match;
  }

  return fallback(options);
}

function resolvePermissionOutcome(params) {
  const options = Array.isArray(params?.options) ? params.options : [];
  const toolKind = params?.toolCall?.kind || null;

  if (options.length === 0) {
    return { outcome: { outcome: "cancelled" } };
  }

  // TODO(security): Do not auto-approve all "read" tool calls.
  // Restrict reads to an allowlist (for example, workspace root and explicitly linked files);
  // otherwise reject/cancel to avoid exposing sensitive local files.
  if (toolKind === "read") {
    const approveOption = selectOptionByKind(
      options,
      ["allow_once", "allow_always"],
      (list) => list[0]
    );
    return {
      outcome: {
        outcome: "selected",
        optionId: approveOption.optionId
      }
    };
  }

  const rejectOption = selectOptionByKind(
    options,
    ["reject_once", "reject_always"],
    (list) => list[list.length - 1]
  );
  return {
    outcome: {
      outcome: "selected",
      optionId: rejectOption.optionId
    }
  };
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
    const toolKind = params?.toolCall?.kind || "missing";
    const toolTitle = params?.toolCall?.title || "";
    const outcome = resolvePermissionOutcome(params);

    if (outcome.outcome.outcome === "cancelled") {
      console.log("[ACP] Cancelled permission request (no options)", {
        kind: toolKind,
        title: toolTitle
      });
      return outcome;
    }

    const options = Array.isArray(params?.options) ? params.options : [];
    const selectedOption = options.find(
      (option) => option?.optionId === outcome.outcome.optionId
    );
    const selectedKind = selectedOption?.kind || "unknown";
    const isReadApproval =
      toolKind === "read" &&
      (selectedKind === "allow_once" || selectedKind === "allow_always");

    if (isReadApproval) {
      console.log("[ACP] Auto-approved read permission", {
        kind: toolKind,
        title: toolTitle,
        option: selectedKind
      });
    } else {
      console.log("[ACP] Rejected non-read permission", {
        kind: toolKind,
        title: toolTitle,
        option: selectedKind
      });
    }

    return outcome;
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
  shutdown,
  __test__: {
    resolvePermissionOutcome
  }
};
