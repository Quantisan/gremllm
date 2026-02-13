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

class MinimalClient {
  constructor() {
    this.toolNamesCalled = new Set();
    this.diffUpdateCount = 0;
  }

  async sessionUpdate({ update }) {
    switch (update.sessionUpdate) {
      case "tool_call": {
        if (!update.rawInput || Object.keys(update.rawInput).length === 0) break;
        const toolName = update._meta?.claudeCode?.toolName || update.kind || "unknown";
        this.toolNamesCalled.add(toolName);
        break;
      }
      case "tool_call_update": {
        const diffItems = Array.isArray(update.content)
          ? update.content.filter((item) => item.type === "diff")
          : [];
        if (diffItems.length > 0) {
          this.diffUpdateCount += 1;
        }
        break;
      }
      default:
        break;
    }
  }

  async requestPermission(params) {
    const firstOption = params.options?.[0];
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

    const mutatedContent = await fs.readFile(tempDocPath, "utf8");
    const mutation = summarizeMutation(sourceContent, mutatedContent);
    const toolNamesCalled = Array.from(client.toolNamesCalled).sort();
    const resourceLinkReadObserved = inferReadObserved(toolNamesCalled);
    const diffObserved = client.diffUpdateCount > 0;

    console.log("=== Native Tools Verdict ===");
    console.log(`stopReason: ${promptResult.stopReason}`);
    console.log(`toolNamesCalled: ${toolNamesCalled.join(", ") || "(none)"}`);
    console.log(`resourceLinkReadObserved: ${resourceLinkReadObserved ? "yes" : "no"}`);
    console.log(`diffInToolCallUpdate: ${diffObserved ? "yes" : "no"}`);
    console.log(`diskMutationObserved: ${mutation.changed ? "yes" : "no"}`);

    if (mutation.changed) {
      console.log(`firstChangedLine: ${mutation.firstChangedLine ?? "unknown"}`);
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
