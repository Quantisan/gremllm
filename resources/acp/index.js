// resources/acp/index.js
const { spawn } = require("node:child_process");
const fs = require("node:fs/promises");
const { Writable, Readable } = require("node:stream");
const acp = require("@agentclientprotocol/sdk");
const { resolvePermissionOutcome } = require("./permission");

function sliceContentByLines(content, line, limit) {
  if (line == null && limit == null) {
    return content;
  }

  const lines = content.split("\n");
  const start = Math.max(0, (line ?? 1) - 1);
  const end = limit != null ? Math.max(start, start + limit) : lines.length;
  return lines.slice(start, end).join("\n");
}

async function readTextFileFromDisk(params) {
  const filePath = params?.path;
  if (typeof filePath !== "string" || filePath.length === 0) {
    throw new Error("readTextFile requires a non-empty path");
  }

  const content = await fs.readFile(filePath, "utf8");
  return {
    content: sliceContentByLines(content, params?.line, params?.limit)
  };
}

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
    // Internal read bridge for ACP edit/write tool flows.
    async readTextFile(params) {
      return readTextFileFromDisk(params);
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
    sliceContentByLines,
    readTextFileFromDisk
  }
};
