#!/usr/bin/env node

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import * as acp from "@agentclientprotocol/sdk";

const PROJECT_ROOT = process.cwd();
const DEFAULT_DOC = path.resolve(PROJECT_ROOT, "docs/plans/2026-02-09-document-first-pivot.md");

const DOC_PATH = process.argv[2] ? path.resolve(PROJECT_ROOT, process.argv[2]) : DEFAULT_DOC;
const DOC_URI = pathToFileURL(DOC_PATH).toString();

const LOCAL_AGENT_ROOT = "/Users/paul/Projects/claude-code-acp";
const LOCAL_AGENT_DIST = path.join(LOCAL_AGENT_ROOT, "dist/index.js");

const AGENT_CMD = process.env.ACP_AGENT_CMD || "claude-code-acp";
const AGENT_ARGS = process.env.ACP_AGENT_ARGS ? process.env.ACP_AGENT_ARGS.split(" ") : [];

const DRY_RUN = true; // Do not write any files.

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class MinimalClient {
  constructor() {
    this.toolCalls = new Map();
    this.diffUpdates = [];
  }

  async sessionUpdate({ sessionId, update }) {
    switch (update.sessionUpdate) {
      case "agent_message_chunk": {
        if (update.content?.type === "text") {
          process.stdout.write(update.content.text);
        }
        break;
      }
      case "tool_call": {
        this.toolCalls.set(update.toolCallId, update);
        break;
      }
      case "tool_call_update": {
        if (Array.isArray(update.content)) {
          const diffs = update.content.filter((item) => item.type === "diff");
          if (diffs.length > 0) {
            this.diffUpdates.push({
              toolCallId: update.toolCallId,
              status: update.status,
              content: diffs,
              locations: update.locations || [],
              rawOutput: update.rawOutput,
            });
          }
        }
        break;
      }
      default:
        break;
    }
  }

  async readTextFile({ path: filePath, line, limit }) {
    const content = await fs.readFile(filePath, "utf8");
    if (line == null && limit == null) {
      return { content };
    }

    const lines = content.split("\n");
    const start = Math.max(0, (line ?? 1) - 1);
    const end = limit != null ? start + limit : lines.length;
    return { content: lines.slice(start, end).join("\n") };
  }

  async writeTextFile({ path: filePath, content }) {
    if (DRY_RUN) {
      console.error(`[dry-run] writeTextFile blocked: ${filePath} (bytes: ${content.length})`);
      return {};
    }

    await fs.writeFile(filePath, content, "utf8");
    return {};
  }
}

async function main() {
  let resolvedCmd = AGENT_CMD;
  let resolvedArgs = AGENT_ARGS;

  if (resolvedCmd === "claude-code-acp") {
    try {
      await fs.access(LOCAL_AGENT_DIST);
      resolvedCmd = "node";
      resolvedArgs = [LOCAL_AGENT_DIST, ...AGENT_ARGS];
    } catch {
      // Keep default command and let spawn error provide next-step hints.
    }
  }

  const agentProcess = spawn(resolvedCmd, resolvedArgs, {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env },
  });

  agentProcess.on("error", (err) => {
    console.error(`Failed to spawn agent (${resolvedCmd}):`, err);
    if (resolvedCmd === "claude-code-acp") {
      console.error(
        `Hint: build local agent at ${LOCAL_AGENT_ROOT} (npm install && npm run build) or set ACP_AGENT_CMD/ACP_AGENT_ARGS.`,
      );
    }
    process.exit(1);
  });

  const input = Writable.toWeb(agentProcess.stdin);
  const output = Readable.toWeb(agentProcess.stdout);

  const client = new MinimalClient();
  const stream = acp.ndJsonStream(input, output);
  const connection = new acp.ClientSideConnection(() => client, stream);

  const init = await connection.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: {
      fs: { readTextFile: true, writeTextFile: true },
      terminal: false,
    },
    clientInfo: {
      name: "gremllm-spike",
      title: "Gremllm Spike 0",
      version: "0.0.1",
    },
  });

  const session = await connection.newSession({
    cwd: PROJECT_ROOT,
    mcpServers: [],
  });

  const promptText =
    "Please read the document at the resource link using ACP Read, then propose a change via ACP Edit: update the head matter Date field to Feb 10, 2026. Do not change anything else. Return edits via the ACP Edit tool so I can review diffs.";

  const promptResult = await connection.prompt({
    sessionId: session.sessionId,
    prompt: [
      { type: "text", text: promptText },
      { type: "resource_link", uri: DOC_URI },
    ],
  });

  await sleep(500);

  console.log("\n\n=== Summary ===");
  console.log(`Agent protocol: ${init.protocolVersion}`);
  console.log(`Stop reason: ${promptResult.stopReason}`);
  console.log(`Diff updates: ${client.diffUpdates.length}`);

  if (client.diffUpdates.length > 0) {
    for (const update of client.diffUpdates) {
      console.log("\n--- Diff ---");
      console.log(`ToolCall: ${update.toolCallId} Status: ${update.status}`);
      console.log(JSON.stringify(update.content, null, 2));
    }
  }

  agentProcess.kill();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
