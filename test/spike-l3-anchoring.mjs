#!/usr/bin/env node
// ============================================================================
// Spike L3: Ambiguous Anchoring
// ============================================================================
//
// RESEARCH QUESTION:
// Does oldText + locations[].line reliably identify the correct edit target
// in a document with near-identical repeated paragraphs?
//
// METHOD:
// 1. Create a synthetic document with 4 near-identical paragraphs,
//    each differing only in one word ("first", "second", "third", "fourth").
// 2. Ask the agent to edit paragraph 3 specifically.
// 3. Capture the tool_call_update diff.
// 4. Check: does oldText uniquely match one location? Does locations[].line
//    point to paragraph 3?
//
// USAGE:
//   node test/spike-l3-anchoring.mjs

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import os from "node:os";
import * as acp from "@agentclientprotocol/sdk";

// ============================================================================
// Synthetic document: 4 near-identical paragraphs, one word differs per para
// ============================================================================
function createSyntheticDoc() {
  return `# Market Analysis Report

## Section Overview

The **first** analysis shows consistent growth across all portfolio companies in the reference period.
The underlying metrics indicate sustained performance above benchmark expectations.
Risk-adjusted returns remain within acceptable parameters for this portfolio class.
Management teams have demonstrated reliable execution against stated objectives.

The **second** analysis shows consistent growth across all portfolio companies in the reference period.
The underlying metrics indicate sustained performance above benchmark expectations.
Risk-adjusted returns remain within acceptable parameters for this portfolio class.
Management teams have demonstrated reliable execution against stated objectives.

The **third** analysis shows consistent growth across all portfolio companies in the reference period.
The underlying metrics indicate sustained performance above benchmark expectations.
Risk-adjusted returns remain within acceptable parameters for this portfolio class.
Management teams have demonstrated reliable execution against stated objectives.

The **fourth** analysis shows consistent growth across all portfolio companies in the reference period.
The underlying metrics indicate sustained performance above benchmark expectations.
Risk-adjusted returns remain within acceptable parameters for this portfolio class.
Management teams have demonstrated reliable execution against stated objectives.
`;
}

// Count how many times needle appears in haystack (non-overlapping)
function countOccurrences(haystack, needle) {
  let count = 0;
  let pos = 0;
  while ((pos = haystack.indexOf(needle, pos)) !== -1) {
    count++;
    pos += needle.length;
  }
  return count;
}

// ============================================================================
// ACP client
// ============================================================================
class SpikeL3Client {
  constructor(docContent) {
    this.docContent = docContent;
    this.diffUpdates = [];
    this.readCalls = 0;
  }

  async sessionUpdate({ sessionId, update }) {
    if (update.sessionUpdate === "tool_call_update" && Array.isArray(update.content)) {
      const diffs = update.content.filter((item) => item.type === "diff");
      if (diffs.length > 0) {
        this.diffUpdates.push({
          toolCallId: update.toolCallId,
          status: update.status,
          content: diffs,
          locations: update.locations || [],
        });
      }
    }
  }

  async readTextFile({ path: filePath, line, limit }) {
    this.readCalls++;
    const content = this.docContent;
    if (line == null && limit == null) return { content };
    const lines = content.split("\n");
    const start = Math.max(0, (line ?? 1) - 1);
    const end = limit != null ? start + limit : lines.length;
    return { content: lines.slice(start, end).join("\n") };
  }

  async writeTextFile() {
    // Dry-run: no disk mutation
    return {};
  }

  async requestPermission(params) {
    const first = params.options?.[0];
    return { outcome: { outcome: "selected", optionId: first?.optionId ?? 0 } };
  }
}

// ============================================================================
// Verdict
// ============================================================================
function printVerdict(docContent, diffUpdates) {
  console.error("\n=== L3 Verdict ===");

  if (diffUpdates.length === 0) {
    console.error("diffCount: 0");
    console.error("ERROR: No diff updates captured. Cannot evaluate anchoring.");
    console.error("Tip: try running with VERBOSE=1 to see all sessionUpdate events.");
    return;
  }

  const diff = diffUpdates[0];
  const item = diff.content[0];
  const { oldText, newText } = item;
  const location = diff.locations[0];

  const occurrences = countOccurrences(docContent, oldText);
  const oldTextUnique = occurrences === 1;

  // Verify the diff targets paragraph 3, not 1/2/4
  const targetedThird = (oldText + newText).includes("third");
  const avoidedOthers =
    !oldText.includes("**first**") &&
    !oldText.includes("**second**") &&
    !oldText.includes("**fourth**");

  console.error(`diffCount: ${diffUpdates.length}`);
  console.error(`oldTextOccurrences: ${occurrences} (in full document)`);
  console.error(`oldTextUnique: ${oldTextUnique ? "yes" : "no"}`);
  console.error(`locationLine: ${location?.line ?? "none"}`);
  console.error(`targetedThird: ${targetedThird ? "yes" : "no"}`);
  console.error(`avoidedOthers: ${avoidedOthers ? "yes" : "no"}`);

  if (oldTextUnique && targetedThird) {
    console.error("anchoringStrategy: content-only (oldText alone disambiguates)");
  } else if (location?.line != null && targetedThird && avoidedOthers) {
    console.error("anchoringStrategy: content+line (line number needed to disambiguate)");
  } else {
    console.error("anchoringStrategy: ambiguous (unreliable anchoring observed)");
  }

  console.error("\noldText (first 150 chars):", JSON.stringify(oldText?.slice(0, 150)));
}

// ============================================================================
// Main
// ============================================================================
async function main() {
  const docContent = createSyntheticDoc();
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "gremllm-spike-l3-"));
  const tempDocPath = path.join(tempDir, "market-analysis.md");
  await fs.writeFile(tempDocPath, docContent, "utf8");
  const docUri = pathToFileURL(tempDocPath).toString();

  console.error(`[spike-l3] Temp doc: ${tempDocPath}`);

  const agentProcess = spawn("npx", ["@zed-industries/claude-code-acp"], {
    stdio: ["pipe", "pipe", "ignore"],
    env: { ...process.env },
  });

  agentProcess.on("error", (err) => {
    console.error("Failed to spawn agent:", err);
    process.exit(1);
  });

  const client = new SpikeL3Client(docContent);
  const stream = acp.ndJsonStream(
    Writable.toWeb(agentProcess.stdin),
    Readable.toWeb(agentProcess.stdout)
  );
  const connection = new acp.ClientSideConnection(() => client, stream);

  await connection.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: { fs: { readTextFile: true, writeTextFile: true }, terminal: false },
    clientInfo: { name: "gremllm-spike-l3", title: "Spike L3: Anchoring", version: "0.0.1" },
  });

  const session = await connection.newSession({
    cwd: process.cwd(),
    mcpServers: [],
    model: "claude-haiku-4-5-20251001",
  });

  const promptText = [
    "Read the linked market analysis document.",
    "Edit ONLY the third paragraph — the one that starts with 'The **third** analysis'.",
    "In that paragraph only, change 'consistent growth' to 'exceptional growth'.",
    "Do not change any other paragraphs.",
  ].join(" ");

  await connection.prompt({
    sessionId: session.sessionId,
    prompt: [
      { type: "text", text: promptText },
      { type: "resource_link", uri: docUri, name: "market-analysis.md" },
    ],
  });

  console.error(`\n[spike-l3] readTextFile calls: ${client.readCalls}`);

  printVerdict(docContent, client.diffUpdates);

  agentProcess.kill();
  await fs.rm(tempDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

