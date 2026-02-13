#!/usr/bin/env node

// ============================================================================
// ACP Spike: Native Tooling + resource_link (no fs callbacks)
// ============================================================================
//
// Core question:
// Can claude-code-acp native tools handle document read/write when we only send
// resource_link context and initialize with clientCapabilities.fs = {}?
//
// This script answers three questions:
// - clientCapabilities: { fs: {} }
// - resource_link in prompt
// 1) Did read behavior occur?
// 2) Did tool_call_update include type:"diff"?
// 3) Was the temp file mutated on disk?
//
// Usage:
//   node test/acp-native-tools-spike.mjs [source-document-path]
//
// Optional env vars:
//   ACP_DOC_PATH      Source document path (overrides CLI arg)
//   ACP_AGENT_CMD     Agent command (default: npx)
//   ACP_AGENT_ARGS    Agent args (default: @zed-industries/claude-code-acp)
//   VERBOSE           Set to 1/true for full JSON dump of all session updates

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

function inferReadObserved(toolNames) {
  return toolNames.some((name) => /read/i.test(name));
}

function compactText(value, maxLength = 140) {
  if (typeof value !== "string") return "";
  const collapsed = value.replace(/\s+/g, " ").trim();
  if (collapsed.length <= maxLength) return collapsed;
  return `${collapsed.slice(0, maxLength - 1)}…`;
}

function compactJson(value, maxLength = 240) {
  const json = JSON.stringify(value);
  if (json.length <= maxLength) return json;
  return `${json.slice(0, maxLength - 1)}…`;
}

class MinimalClient {
  constructor() {
    this.toolNamesCalled = new Set();
    this.diffUpdateCount = 0;
    this.eventCounts = {};
    this.toolCalls = [];
    this.toolCallUpdates = [];
    this.reasoningSamples = [];
    this.thoughtBuffer = "";
    this.assistantMessageChunkCount = 0;
    this.assistantTextBytes = 0;
    this.permissionRequests = [];
  }

  recordEvent(updateType) {
    this.eventCounts[updateType] = (this.eventCounts[updateType] || 0) + 1;
  }

  recordThoughtSegment(segment) {
    const excerpt = compactText(segment, 120);
    if (excerpt.length > 0 && this.reasoningSamples.length < 8) {
      this.reasoningSamples.push(excerpt);
    }
    if (!VERBOSE && excerpt.length > 0) {
      process.stderr.write(`\n\x1b[2m[thought] ${excerpt}\x1b[0m`);
    }
  }

  flushThoughtBuffer({ force = false } = {}) {
    if (this.thoughtBuffer.length === 0) {
      return;
    }

    let working = this.thoughtBuffer;
    const segments = [];

    while (working.length > 0) {
      const boundaryMatch = working.match(/\n|[.!?](?=\s)/);
      if (!boundaryMatch) {
        if (!force && segments.length === 0) {
          break;
        }
        const remainder = compactText(working, 400);
        if (remainder.length > 0) {
          segments.push(remainder);
        }
        working = "";
        break;
      }

      const splitIndex = (boundaryMatch.index ?? 0) + 1;
      const segment = compactText(working.slice(0, splitIndex), 400);
      if (segment.length > 0) {
        segments.push(segment);
      }
      working = working.slice(splitIndex).trimStart();
    }

    this.thoughtBuffer = working;
    segments.forEach((segment) => this.recordThoughtSegment(segment));
  }

  async sessionUpdate({ update }) {
    const updateType = update.sessionUpdate;
    this.recordEvent(updateType);

    if (VERBOSE) {
      console.error(`\n[${updateType}]`, JSON.stringify(update, null, 2));
    }

    if (updateType !== "agent_thought_chunk") {
      this.flushThoughtBuffer({ force: true });
    }

    switch (updateType) {
      case "agent_message_chunk": {
        if (update.content?.type === "text") {
          const text = update.content.text || "";
          this.assistantMessageChunkCount += 1;
          this.assistantTextBytes += text.length;

          if (!VERBOSE) {
            process.stdout.write(text);
          }
        }
        break;
      }
      case "agent_thought_chunk": {
        if (update.content?.type === "text") {
          this.thoughtBuffer += update.content.text || "";
          this.flushThoughtBuffer();
        }
        break;
      }
      case "tool_call": {
        if (!update.rawInput || Object.keys(update.rawInput).length === 0) break;

        const toolName = update._meta?.claudeCode?.toolName || update.kind || "unknown";
        this.toolNamesCalled.add(toolName);
        const title = update.title || toolName;
        const record = {
          toolCallId: update.toolCallId || null,
          toolName,
          title,
          rawInput: update.rawInput,
        };
        this.toolCalls.push(record);

        if (!VERBOSE) {
          console.error(`\n\x1b[1m[tool_call] ${toolName}\x1b[0m - ${title}`);
          console.error(`  Input: ${compactJson(update.rawInput)}`);
        }
        break;
      }
      case "tool_call_update": {
        const contentItems = Array.isArray(update.content) ? update.content : [];
        const diffItems = contentItems.filter((item) => item.type === "diff");
        const contentTypes = Array.from(
          new Set(contentItems.map((item) => item?.type).filter(Boolean)),
        );

        if (diffItems.length > 0) {
          this.diffUpdateCount += 1;
        }

        const summary = {
          toolCallId: update.toolCallId || null,
          status: update.status || "unknown",
          diffCount: diffItems.length,
          contentTypes,
          hasDiff: diffItems.length > 0,
        };
        this.toolCallUpdates.push(summary);

        if (!VERBOSE) {
          const prefix = diffItems.length > 0 ? "\x1b[32m[tool_call_update]\x1b[0m" : "[tool_call_update]";
          console.error(
            `\n${prefix} id=${summary.toolCallId || "unknown"} status=${summary.status} diffCount=${summary.diffCount} contentTypes=${summary.contentTypes.join(",") || "(none)"}`,
          );
        }
        break;
      }
      case "available_commands_update": {
        const cmdCount = update.commands?.length || update.availableCommands?.length || 0;
        if (!VERBOSE && cmdCount > 0) {
          console.error(`\n[available_commands_update] commands=${cmdCount}`);
        }
        break;
      }
      default:
        if (!VERBOSE) {
          console.error(`\n[${updateType}]`);
        }
        break;
    }
  }

  async requestPermission(params) {
    this.flushThoughtBuffer({ force: true });

    const optionCount = params.options?.length || 0;
    const firstOption = params.options?.[0];
    const selectedOptionId = firstOption?.optionId ?? 0;
    this.permissionRequests.push({
      optionCount,
      selectedOptionId,
    });

    if (!VERBOSE) {
      console.error(
        `\n[request_permission] options=${optionCount} selectedOptionId=${selectedOptionId}`,
      );
    }

    return {
      outcome: {
        outcome: "selected",
        optionId: selectedOptionId,
      },
    };
  }
}

async function main() {
  const sourceContent = await fs.readFile(SOURCE_DOC_PATH, "utf8");

  // Native Edit/Write can mutate disk directly in this spike (no fs callbacks,
  // no write interception), so we run against a temp copy to protect real files
  // while still observing true on-disk mutation behavior.
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "gremllm-acp-native-tools-"));
  const tempDocPath = path.join(tempDir, path.basename(SOURCE_DOC_PATH));
  await fs.copyFile(SOURCE_DOC_PATH, tempDocPath);

  const tempDocUri = pathToFileURL(tempDocPath).toString();

  const agentProcess = spawn(AGENT_CMD, AGENT_ARGS, {
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env },
  });

  try {
    const input = Writable.toWeb(agentProcess.stdin);
    const output = Readable.toWeb(agentProcess.stdout);

    const client = new MinimalClient();
    const stream = acp.ndJsonStream(input, output);
    const connection = new acp.ClientSideConnection(() => client, stream);

    await connection.initialize({
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
    });

    const session = await connection.newSession({
      cwd: PROJECT_ROOT,
      mcpServers: [],
      model: "claude-haiku-4-5-20251001",
    });

    const promptText =
      "Read the linked document, then make exactly one edit: replace the first line '# PE Due Diligence Report (Lorem Ipsum)' with '# PE Due Diligence Report (Lorem Ipsum, Revised)'. Do not change anything else.";

    const promptResult = await connection.prompt({
      sessionId: session.sessionId,
      prompt: [
        { type: "text", text: promptText },
        { type: "resource_link", uri: tempDocUri, name: path.basename(tempDocPath) },
      ],
    });
    client.flushThoughtBuffer({ force: true });

    const mutatedContent = await fs.readFile(tempDocPath, "utf8");
    const mutation = summarizeMutation(sourceContent, mutatedContent);
    const toolNamesCalled = Array.from(client.toolNamesCalled).sort();
    const resourceLinkReadObserved = inferReadObserved(toolNamesCalled);
    const diffObserved = client.diffUpdateCount > 0;

    console.log("");
    console.log("=== Native Tools Verdict ===");
    console.log(`stopReason: ${promptResult.stopReason}`);
    console.log(`toolNamesCalled: ${toolNamesCalled.join(", ") || "(none)"}`);
    console.log(`resourceLinkReadObserved: ${resourceLinkReadObserved ? "yes" : "no"}`);
    console.log(`diffInToolCallUpdate: ${diffObserved ? "yes" : "no"}`);
    console.log(`diskMutationObserved: ${mutation.changed ? "yes" : "no"}`);

    if (mutation.changed) {
      console.log(`firstChangedLine: ${mutation.firstChangedLine ?? "unknown"}`);
    }

    console.log("");
    console.log("=== Native Tools Observability ===");
    console.log(`verboseMode: ${VERBOSE ? "enabled" : "disabled (set VERBOSE=1 to enable)"}`);
    console.log(`assistantMessageChunks: ${client.assistantMessageChunkCount}`);
    console.log(`assistantTextBytes: ${client.assistantTextBytes}`);
    console.log(`reasoningSamplesCaptured: ${client.reasoningSamples.length}`);
    console.log(`permissionRequests: ${client.permissionRequests.length}`);

    console.log("\neventCounts:");
    const eventEntries = Object.entries(client.eventCounts).sort(([a], [b]) => a.localeCompare(b));
    for (const [eventType, count] of eventEntries) {
      console.log(`  ${eventType}: ${count}`);
    }

    console.log("\ntoolCalls:");
    if (client.toolCalls.length === 0) {
      console.log("  (none)");
    } else {
      client.toolCalls.forEach((tool, idx) => {
        console.log(
          `  ${idx + 1}. ${tool.toolName} (id: ${tool.toolCallId || "unknown"}) input=${compactJson(tool.rawInput, 180)}`,
        );
      });
    }

    console.log("\ntoolCallUpdates:");
    if (client.toolCallUpdates.length === 0) {
      console.log("  (none)");
    } else {
      client.toolCallUpdates.forEach((updateSummary, idx) => {
        console.log(
          `  ${idx + 1}. id=${updateSummary.toolCallId || "unknown"} status=${updateSummary.status} diffCount=${updateSummary.diffCount} contentTypes=${updateSummary.contentTypes.join(",") || "(none)"}`,
        );
      });
    }

    console.log("\nreasoningSamples:");
    if (client.reasoningSamples.length === 0) {
      console.log("  (none)");
    } else {
      client.reasoningSamples.forEach((sample, idx) => {
        console.log(`  ${idx + 1}. ${sample}`);
      });
    }
  } finally {
    if (!agentProcess.killed) {
      agentProcess.kill();
    }
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
