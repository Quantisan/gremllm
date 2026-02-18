// resources/acp/index.js
const { spawn } = require("node:child_process");
const { Writable, Readable } = require("node:stream");
const acp = require("@agentclientprotocol/sdk");
const { resolvePermissionOutcome } = require("./permission");

function createConnection(callbacks) {
  const subprocess = spawn("npx", ["@zed-industries/claude-code-acp"], {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env }
  });

  const client = {
    async sessionUpdate(params) {
      if (callbacks.onSessionUpdate) {
        callbacks.onSessionUpdate(params);
      }
    },
    async requestPermission(params) {
      return resolvePermissionOutcome(params);
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

module.exports = { createConnection };
