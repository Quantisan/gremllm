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
//   VERBOSE=1 node test/spike-l3-anchoring.mjs

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import os from "node:os";
import * as acp from "@agentclientprotocol/sdk";

const VERBOSE = process.env.VERBOSE === "1";

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

function findThirdParagraphLineRange(docContent) {
  const lines = docContent.split("\n");
  const startIndex = lines.findIndex((line) => line.includes("The **third** analysis"));
  if (startIndex === -1) return null;

  let endIndex = startIndex;
  while (endIndex + 1 < lines.length && lines[endIndex + 1].trim() !== "") {
    endIndex++;
  }

  return { start: startIndex + 1, end: endIndex + 1 };
}

function isLineInRange(line, range) {
  return line != null && range != null && line >= range.start && line <= range.end;
}

function rangeToString(range) {
  if (!range) return "unknown";
  return `${range.start}-${range.end}`;
}

// ============================================================================
// ACP client
// ============================================================================
class SpikeL3Client {
  constructor(docContent, options = {}) {
    this.docContent = docContent;
    this.diffUpdates = [];
    this.readCalls = 0;
    this.readCallDetails = [];
    this.verbose = options.verbose === true;
  }

  async sessionUpdate({ sessionId, update }) {
    if (this.verbose && update.sessionUpdate === "tool_call_update") {
      const contentCount = Array.isArray(update.content) ? update.content.length : 0;
      const locationCount = Array.isArray(update.locations) ? update.locations.length : 0;
      console.error(
        `[spike-l3][verbose] tool_call_update id=${update.toolCallId} status=${update.status} contentItems=${contentCount} locations=${locationCount}`
      );
    }

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
    if (line == null && limit == null) {
      this.readCallDetails.push({
        path: filePath,
        line: null,
        limit: null,
        returnedLines: content.split("\n").length,
      });
      return { content };
    }

    const lines = content.split("\n");
    const start = Math.max(0, (line ?? 1) - 1);
    const end = limit != null ? start + limit : lines.length;
    const slice = lines.slice(start, end).join("\n");
    this.readCallDetails.push({
      path: filePath,
      line: line ?? null,
      limit: limit ?? null,
      returnedLines: slice.length === 0 ? 0 : slice.split("\n").length,
    });
    return { content: slice };
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
function printVerdict(docContent, diffUpdates, readCallDetails) {
  console.error("\n=== L3 Verdict ===");
  const expectedRange = findThirdParagraphLineRange(docContent);
  console.error(`expectedThirdParagraphLines: ${rangeToString(expectedRange)}`);
  console.error(`readTextFile calls: ${readCallDetails.length}`);
  for (let i = 0; i < readCallDetails.length; i++) {
    const call = readCallDetails[i];
    console.error(
      `[read ${i + 1}] path=${call.path} line=${call.line ?? "none"} limit=${call.limit ?? "none"} returnedLines=${call.returnedLines}`
    );
  }

  if (diffUpdates.length === 0) {
    console.error("diffCount: 0");
    console.error("ERROR: No diff updates captured. Cannot evaluate anchoring.");
    console.error("Tip: try running with VERBOSE=1 for more event visibility.");
    return;
  }

  let diffItemCount = 0;
  let oldTextUniqueCount = 0;
  let targetedThirdCount = 0;
  let ambiguousCount = 0;
  let contentOnlyCount = 0;
  let contentPlusLineCount = 0;
  let locationCheckedCount = 0;
  let locationInRangeCount = 0;

  console.error(`diffCount: ${diffUpdates.length}`);
  for (let updateIndex = 0; updateIndex < diffUpdates.length; updateIndex++) {
    const update = diffUpdates[updateIndex];
    console.error(
      `[diff-update ${updateIndex + 1}] toolCallId=${update.toolCallId} status=${update.status} diffItems=${update.content.length} locations=${update.locations.length}`
    );

    for (let itemIndex = 0; itemIndex < update.content.length; itemIndex++) {
      diffItemCount++;
      const item = update.content[itemIndex];
      const oldText = item.oldText ?? "";
      const newText = item.newText ?? "";
      const location = update.locations[itemIndex] ?? update.locations[0] ?? null;
      const locationLine = location?.line ?? null;
      const locationInRange = isLineInRange(locationLine, expectedRange);
      const occurrences = countOccurrences(docContent, oldText);
      const oldTextUnique = occurrences === 1;
      const targetedThird = (oldText + newText).includes("third");
      const avoidedOthers =
        !oldText.includes("**first**") &&
        !oldText.includes("**second**") &&
        !oldText.includes("**fourth**");

      if (oldTextUnique) oldTextUniqueCount++;
      if (targetedThird) targetedThirdCount++;
      if (locationLine != null && expectedRange != null) {
        locationCheckedCount++;
        if (locationInRange) locationInRangeCount++;
      }

      let strategy;
      if (oldTextUnique && targetedThird) {
        strategy = "content-only";
        contentOnlyCount++;
      } else if (locationLine != null && locationInRange && targetedThird && avoidedOthers) {
        strategy = "content+line";
        contentPlusLineCount++;
      } else {
        strategy = "ambiguous";
        ambiguousCount++;
      }

      console.error(
        `[diff-item ${updateIndex + 1}.${itemIndex + 1}] path=${item.path ?? "unknown"} oldTextOccurrences=${occurrences} oldTextUnique=${oldTextUnique ? "yes" : "no"} locationLine=${locationLine ?? "none"} locationInExpectedRange=${locationInRange ? "yes" : "no"} targetedThird=${targetedThird ? "yes" : "no"} avoidedOthers=${avoidedOthers ? "yes" : "no"} anchoringStrategy=${strategy}`
      );
      console.error(
        `oldText (first 150 chars): ${JSON.stringify(oldText.slice(0, 150))}`
      );
    }
  }

  const finalStrategy =
    ambiguousCount > 0
      ? "ambiguous (at least one diff item unreliable)"
      : contentPlusLineCount > 0
        ? "content+line (line verification required in at least one diff item)"
        : "content-only (oldText disambiguates all diff items)";

  console.error(`\ndiffItemCount: ${diffItemCount}`);
  console.error(`oldTextUniqueCount: ${oldTextUniqueCount}/${diffItemCount}`);
  console.error(`targetedThirdCount: ${targetedThirdCount}/${diffItemCount}`);
  console.error(`locationChecksInRange: ${locationInRangeCount}/${locationCheckedCount}`);
  console.error(`contentOnlyItems: ${contentOnlyCount}`);
  console.error(`contentPlusLineItems: ${contentPlusLineCount}`);
  console.error(`ambiguousItems: ${ambiguousCount}`);
  console.error(`anchoringStrategy: ${finalStrategy}`);
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
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...process.env },
  });

  agentProcess.on("error", (err) => {
    console.error("Failed to spawn agent:", err);
    process.exit(1);
  });
  agentProcess.on("exit", (code, signal) => {
    if (VERBOSE || code !== 0) {
      console.error(`[spike-l3] agent exit code=${code ?? "none"} signal=${signal ?? "none"}`);
    }
  });
  if (VERBOSE && agentProcess.stderr) {
    agentProcess.stderr.on("data", (chunk) => {
      const text = chunk.toString().trimEnd();
      if (text.length > 0) {
        console.error(`[spike-l3][agent-stderr] ${text}`);
      }
    });
  }

  const client = new SpikeL3Client(docContent, { verbose: VERBOSE });
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

  printVerdict(docContent, client.diffUpdates, client.readCallDetails);

  agentProcess.kill();
  await fs.rm(tempDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
