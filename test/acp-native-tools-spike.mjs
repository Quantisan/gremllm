#!/usr/bin/env node

// ============================================================================
// ACP Spike: Native Tooling + resource_link (no fs callbacks)
// ============================================================================
//
// This spike validates whether claude-code-acp native filesystem tooling can
// handle document reads/writes with only:
// - clientCapabilities: { fs: {} }
// - prompt context via resource_link
//
// Key observations captured:
// 1. Which tool names appear (built-in vs mcp__acp__ wrappers)
// 2. Whether tool_call_update includes diff-shaped content
// 3. Whether the temp document is mutated on disk
//
// Usage:
//   node test/acp-native-tools-spike.mjs [source-document-path]
//
// Optional env vars:
//   ACP_DOC_PATH      Source document path (overrides CLI arg)
//   ACP_AGENT_CMD     Agent command (default: npx)
//   ACP_AGENT_ARGS    Agent args (default: @zed-industries/claude-code-acp)
//   VERBOSE           1/true to dump all session updates as JSON
//   KEEP_TEMP         1/true to keep temp directory after run

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import os from "node:os";
import fs from "node:fs/promises";
import * as acp from "@agentclientprotocol/sdk";

const PROJECT_ROOT = process.cwd();
const DEFAULT_DOC = path.resolve(PROJECT_ROOT, "resources/pe-dd-report-lorem.md");

const SOURCE_DOC_PATH = process.env.ACP_DOC_PATH
  ? path.resolve(PROJECT_ROOT, process.env.ACP_DOC_PATH)
  : process.argv[2]
    ? path.resolve(PROJECT_ROOT, process.argv[2])
    : DEFAULT_DOC;

const AGENT_CMD = process.env.ACP_AGENT_CMD || "npx";
const AGENT_ARGS = process.env.ACP_AGENT_ARGS
  ? process.env.ACP_AGENT_ARGS.split(" ")
  : ["@zed-industries/claude-code-acp"];

const VERBOSE = process.env.VERBOSE === "true" || process.env.VERBOSE === "1";
const KEEP_TEMP = process.env.KEEP_TEMP === "true" || process.env.KEEP_TEMP === "1";

function summarizeMutation(before, after) {
  if (before === after) {
    return { changed: false };
  }

  const beforeLines = before.split("\n");
  const afterLines = after.split("\n");
  const max = Math.max(beforeLines.length, afterLines.length);

  for (let i = 0; i < max; i += 1) {
    if (beforeLines[i] !== afterLines[i]) {
      return {
        changed: true,
        firstChangedLine: i + 1,
        beforeLine: beforeLines[i] ?? "",
        afterLine: afterLines[i] ?? "",
      };
    }
  }

  return { changed: true };
}

class MinimalClient {
  constructor() {
    this.toolCalls = [];
    this.toolCallUpdates = [];
    this.eventCounts = {};
  }

  async sessionUpdate({ update }) {
    const updateType = update.sessionUpdate;
    this.eventCounts[updateType] = (this.eventCounts[updateType] || 0) + 1;

    if (VERBOSE) {
      console.error(`\n[${updateType}]`, JSON.stringify(update, null, 2));
      return;
    }

    switch (updateType) {
      case "agent_message_chunk": {
        if (update.content?.type === "text") {
          process.stdout.write(update.content.text);
        }
        break;
      }
      case "agent_thought_chunk": {
        if (update.content?.type === "text") {
          process.stderr.write(`\x1b[2m${update.content.text}\x1b[0m`);
        }
        break;
      }
      case "tool_call": {
        const toolName = update._meta?.claudeCode?.toolName || update.kind || "unknown";
        const callRecord = {
          toolName,
          title: update.title || "",
          toolCallId: update.toolCallId || "",
          rawInput: update.rawInput || {},
        };
        this.toolCalls.push(callRecord);

        console.error(`\n\x1b[1m[tool_call]\x1b[0m ${toolName}`);
        if (callRecord.title) {
          console.error(`  title: ${callRecord.title}`);
        }
        console.error(`  input: ${JSON.stringify(callRecord.rawInput, null, 2)}`);
        break;
      }
      case "tool_call_update": {
        const diffItems = Array.isArray(update.content)
          ? update.content.filter((item) => item.type === "diff")
          : [];
        const updateRecord = {
          toolName: update._meta?.claudeCode?.toolName || update.kind || "unknown",
          toolCallId: update.toolCallId || "",
          status: update.status || "",
          diffCount: diffItems.length,
          contentTypes: Array.isArray(update.content) ? update.content.map((item) => item.type || "unknown") : [],
          rawUpdate: update,
        };
        this.toolCallUpdates.push(updateRecord);

        console.error(`\n\x1b[36m[tool_call_update]\x1b[0m id=${updateRecord.toolCallId} status=${updateRecord.status}`);
        console.error(`  content types: ${updateRecord.contentTypes.join(", ") || "(none)"}`);
        if (diffItems.length > 0) {
          console.error(`  diff items: ${diffItems.length}`);
        }
        break;
      }
      case "available_commands_update": {
        const count = update.commands?.length || update.availableCommands?.length || 0;
        console.error(`\n[available_commands_update] ${count} command(s) advertised`);
        break;
      }
      default: {
        console.error(`\n[${updateType}]`);
        break;
      }
    }
  }

  async requestPermission(params) {
    const firstOption = params.options?.[0];
    console.error(
      `\n[request_permission] ${params.message || "(no message)"} -> selecting option ${
        firstOption?.optionId ?? 0
      }`,
    );

    return {
      outcome: {
        outcome: "selected",
        optionId: firstOption?.optionId ?? 0,
      },
    };
  }
}

async function main() {
  const sourceContent = await fs.readFile(SOURCE_DOC_PATH, "utf8");

  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "gremllm-acp-native-tools-"));
  const tempDocPath = path.join(tempDir, path.basename(SOURCE_DOC_PATH));
  await fs.copyFile(SOURCE_DOC_PATH, tempDocPath);

  const tempDocUri = pathToFileURL(tempDocPath).toString();

  console.log("=== Setup ===");
  console.log(`Source doc: ${SOURCE_DOC_PATH}`);
  console.log(`Temp doc:   ${tempDocPath}`);
  console.log(`Temp uri:   ${tempDocUri}`);
  console.log(`Agent:      ${AGENT_CMD} ${AGENT_ARGS.join(" ")}`);
  console.log(`Verbose:    ${VERBOSE ? "enabled" : "disabled"}`);

  const agentProcess = spawn(AGENT_CMD, AGENT_ARGS, {
    stdio: ["pipe", "pipe", "ignore"],
    env: { ...process.env },
  });

  const agentExit = new Promise((resolve, reject) => {
    agentProcess.once("error", reject);
    agentProcess.once("exit", (code, signal) => resolve({ code, signal }));
  });

  const withAgentLiveness = async (label, promise) => {
    const result = await Promise.race([
      promise.then((value) => ({ kind: "ok", value })),
      agentExit.then((info) => ({ kind: "exit", info })),
    ]);

    if (result.kind === "exit") {
      const code = result.info.code ?? "null";
      const signal = result.info.signal ?? "none";
      throw new Error(`Agent process exited during ${label} (code=${code}, signal=${signal})`);
    }

    return result.value;
  };

  let cleanedUp = false;
  const cleanup = async () => {
    if (cleanedUp) return;
    cleanedUp = true;

    if (!agentProcess.killed) {
      agentProcess.kill();
    }
    if (!KEEP_TEMP) {
      await fs.rm(tempDir, { recursive: true, force: true });
    }
  };

  try {
    const input = Writable.toWeb(agentProcess.stdin);
    const output = Readable.toWeb(agentProcess.stdout);

    const client = new MinimalClient();
    const stream = acp.ndJsonStream(input, output);
    const connection = new acp.ClientSideConnection(() => client, stream);

    const init = await withAgentLiveness("initialize", connection.initialize({
      protocolVersion: acp.PROTOCOL_VERSION,
      clientCapabilities: {
        fs: {},
        terminal: false,
      },
      clientInfo: {
        name: "gremllm-native-tools-spike",
        title: "Gremllm Native Tooling Spike",
        version: "0.0.1",
      },
    }));

    const session = await withAgentLiveness("newSession", connection.newSession({
      cwd: PROJECT_ROOT,
      mcpServers: [],
      model: "claude-haiku-4-5-20251001",
    }));

    const promptText =
      "Read the linked document, then make exactly one edit: replace the first line '# PE Due Diligence Report (Lorem Ipsum)' with '# PE Due Diligence Report (Lorem Ipsum, Revised)'. Do not change anything else.";

    const promptResult = await withAgentLiveness("prompt", connection.prompt({
      sessionId: session.sessionId,
      prompt: [
        { type: "text", text: promptText },
        { type: "resource_link", uri: tempDocUri, name: path.basename(tempDocPath) },
      ],
    }));

    const mutatedContent = await fs.readFile(tempDocPath, "utf8");
    const mutation = summarizeMutation(sourceContent, mutatedContent);

    const toolNamesCalled = [...new Set(client.toolCalls.map((c) => c.toolName))];
    const toolNamesUpdated = [...new Set(client.toolCallUpdates.map((u) => u.toolName))];
    const diffUpdateCount = client.toolCallUpdates.filter((u) => u.diffCount > 0).length;

    console.log("\n\n=== Summary ===");
    console.log(`Agent protocol: ${init.protocolVersion}`);
    console.log(`Stop reason: ${promptResult.stopReason}`);
    console.log(`Tool names (tool_call): ${toolNamesCalled.join(", ") || "(none)"}`);
    console.log(`Tool names (tool_call_update): ${toolNamesUpdated.join(", ") || "(none)"}`);
    console.log(`tool_call count: ${client.toolCalls.length}`);
    console.log(`tool_call_update count: ${client.toolCallUpdates.length}`);
    console.log(`tool_call_update entries with diff content: ${diffUpdateCount}`);
    console.log(`Disk mutation observed: ${mutation.changed ? "YES" : "NO"}`);

    if (mutation.changed) {
      console.log(`First changed line: ${mutation.firstChangedLine ?? "(unknown)"}`);
      if (mutation.beforeLine != null && mutation.afterLine != null) {
        console.log(`Before: ${mutation.beforeLine}`);
        console.log(`After:  ${mutation.afterLine}`);
      }
    }

    console.log("\n--- Event Counts ---");
    console.log(JSON.stringify(client.eventCounts, null, 2));

    console.log("\n--- tool_call Records ---");
    console.log(JSON.stringify(client.toolCalls, null, 2));

    console.log("\n--- tool_call_update Records ---");
    console.log(JSON.stringify(client.toolCallUpdates, null, 2));
  } finally {
    await cleanup();
    if (KEEP_TEMP) {
      console.log(`\nTemp directory retained: ${tempDir}`);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
