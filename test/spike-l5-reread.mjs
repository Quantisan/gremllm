#!/usr/bin/env node
// ============================================================================
// Spike L5: Re-Read Hint Effectiveness
// ============================================================================
//
// RESEARCH QUESTION:
// Does sending resource_link + a re-read hint on each prompt cause the agent
// to re-read the file after between-prompt disk changes?
// Is the hint necessary, or does the agent re-read by default?
//
// METHOD:
// Two conditions, each with a fresh ACP session and a fresh copy of the doc:
//
// Condition A (with hint):
//   Turn 1: prompt includes re-read hint + resource_link → edit "12%" to "15%"
//            → let write happen to disk
//   Turn 2: prompt includes re-read hint + resource_link → different edit
//            → check if readTextFile fires and returns updated content
//
// Condition B (without hint, control):
//   Same structure but no re-read hint text in prompts
//
// SUCCESS CRITERIA:
// - turn2ReadCount > 0: agent called readTextFile in turn 2 (not skipped)
// - turn2SawUpdatedContent: the content it read matches the post-turn-1 file
//
// USAGE:
//   node test/spike-l5-reread.mjs
import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import os from "node:os";
import * as acp from "@agentclientprotocol/sdk";

// ============================================================================
// Document
// ============================================================================
function createInitialDoc() {
  return `# Investment Memo: Alpha Portfolio

## Executive Summary

Alpha Portfolio delivered strong results in Q3 2025, with revenue growing 12% year-over-year.
The management team exceeded targets across all key performance indicators.
Market position remains strong with no significant competitive threats identified.

## Key Findings

The due diligence process confirmed robust unit economics and a clear path to profitability.
Customer retention rates of 94% demonstrate strong product-market fit.
The technology moat is defensible and continues to widen.
`;
}

// ============================================================================
// Simple hash for content comparison
// ============================================================================
function simpleHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}

function extractToolAction(update) {
  if (typeof update.title === "string" && update.title.trim().length > 0) {
    return update.title.trim().split(/\s+/)[0];
  }
  const toolName = update._meta?.claudeCode?.toolName || update.kind || "";
  if (toolName.includes("__")) {
    return toolName.split("__").pop();
  }
  return toolName || "unknown";
}

function extractToolPath(update, fallbackPath = null) {
  const rawInput = update.rawInput || {};
  const candidate =
    rawInput.path ||
    rawInput.file_path ||
    rawInput.filePath ||
    rawInput.target_path ||
    rawInput.targetPath;
  if (typeof candidate === "string" && candidate.length > 0) {
    return candidate;
  }
  if (Array.isArray(update.locations)) {
    const locationWithPath = update.locations.find((loc) => typeof loc?.path === "string");
    if (locationWithPath) return locationWithPath.path;
  }
  if (Array.isArray(update.content)) {
    const diffWithPath = update.content.find((item) => typeof item?.path === "string");
    if (diffWithPath) return diffWithPath.path;
  }
  return fallbackPath;
}

function logToolEvent(eventType, action, filePath) {
  const summary = {
    action: action || "unknown",
    path: filePath || "unknown",
  };
  console.error(`[${eventType}] ${JSON.stringify(summary)}`);
}

// ============================================================================
// ACP client factory — fresh per condition
// ============================================================================
function createClient(docPath) {
  // Track each readTextFile call: { contentHash, contentSnippet }
  const readCalls = [];
  const toolCallsById = new Map();

  const client = {
    readCalls,

    async sessionUpdate({ update }) {
      const updateType = update.sessionUpdate;

      if (updateType === "tool_call") {
        if (!update.rawInput || Object.keys(update.rawInput).length === 0) return;
        const action = extractToolAction(update);
        const filePath = extractToolPath(update, docPath);
        if (update.toolCallId) {
          toolCallsById.set(update.toolCallId, { action, path: filePath });
        }
        logToolEvent("tool_call", action, filePath);
        return;
      }
    },

    async readTextFile({ path: filePath, line, limit }) {
      const content = await fs.readFile(filePath, "utf8");
      readCalls.push({
        contentHash: simpleHash(content),
        contentSnippet: content.slice(0, 100),
      });
      if (line == null && limit == null) return { content };
      const lines = content.split("\n");
      const start = Math.max(0, (line ?? 1) - 1);
      const end = limit != null ? start + limit : lines.length;
      return { content: lines.slice(start, end).join("\n") };
    },

    async writeTextFile({ path: filePath, content }) {
      // Allow real writes — disk changes between turns is the whole point
      await fs.writeFile(filePath, content, "utf8");
      return {};
    },

    async requestPermission(params) {
      const first = params.options?.[0];
      return { outcome: { outcome: "selected", optionId: first?.optionId ?? 0 } };
    },
  };

  return client;
}

// ============================================================================
// Run one condition (with or without hint)
// ============================================================================
async function runCondition(label, docPath, useHint) {
  const docUri = pathToFileURL(docPath).toString();
  const originalContent = await fs.readFile(docPath, "utf8");
  const originalHash = simpleHash(originalContent);

  const agentProcess = spawn("npx", ["@zed-industries/claude-code-acp"], {
    stdio: ["pipe", "pipe", "ignore"],
    env: { ...process.env },
  });

  agentProcess.on("error", (err) => {
    console.error(`[${label}] Failed to spawn agent:`, err);
    process.exit(1);
  });

  const client = createClient(docPath);
  const stream = acp.ndJsonStream(
    Writable.toWeb(agentProcess.stdin),
    Readable.toWeb(agentProcess.stdout)
  );
  const connection = new acp.ClientSideConnection(() => client, stream);

  await connection.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: { fs: { readTextFile: true, writeTextFile: true }, terminal: false },
    clientInfo: { name: "gremllm-spike-l5", title: "Spike L5: Re-read", version: "0.0.1" },
  });

  const session = await connection.newSession({
    cwd: process.cwd(),
    mcpServers: [],
    model: "claude-haiku-4-5-20251001",
  });

  const hint = useHint
    ? "The document at the linked resource may have changed since you last read it. Always re-read it before making edits."
    : "";

  // ---- Turn 1 ----
  const turn1ReadsBefore = client.readCalls.length;

  await connection.prompt({
    sessionId: session.sessionId,
    prompt: [
      {
        type: "text",
        text: [
          hint,
          "Read the linked investment memo and change '12%' to '15%' in the executive summary.",
          "Make no other changes.",
        ]
          .filter(Boolean)
          .join(" "),
      },
      { type: "resource_link", uri: docUri, name: "investment-memo.md" },
    ],
  });

  const turn1ReadCount = client.readCalls.length - turn1ReadsBefore;

  // Check if the agent actually wrote to disk
  const afterTurn1Content = await fs.readFile(docPath, "utf8");
  const afterTurn1Hash = simpleHash(afterTurn1Content);
  const turn1WroteNewContent = afterTurn1Hash !== originalHash;

  // ---- Turn 2 ----
  const turn2ReadsBefore = client.readCalls.length;

  await connection.prompt({
    sessionId: session.sessionId,
    prompt: [
      {
        type: "text",
        text: [
          hint,
          "Read the linked investment memo and change 'strong results' to 'outstanding results' in the executive summary.",
          "Make no other changes.",
        ]
          .filter(Boolean)
          .join(" "),
      },
      { type: "resource_link", uri: docUri, name: "investment-memo.md" },
    ],
  });

  const turn2ReadCount = client.readCalls.length - turn2ReadsBefore;
  const lastRead = client.readCalls[client.readCalls.length - 1];

  // Success: agent read in turn 2 AND saw the updated content (post-turn-1 write)
  const turn2SawUpdatedContent =
    turn2ReadCount > 0 && lastRead?.contentHash === afterTurn1Hash;

  agentProcess.kill();

  return {
    label,
    useHint,
    turn1ReadCount,
    turn1WroteNewContent,
    turn2ReadCount,
    turn2SawUpdatedContent,
    // diagnostics
    originalHash,
    afterTurn1Hash,
    turn2ContentHash: lastRead?.contentHash,
    turn2ContentSnippet: lastRead?.contentSnippet,
  };
}

// ============================================================================
// Main
// ============================================================================
async function main() {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "gremllm-spike-l5-"));

  // Condition A: with hint
  const docPathA = path.join(tempDir, "memo-a.md");
  await fs.writeFile(docPathA, createInitialDoc(), "utf8");
  console.error("[spike-l5] Running condition A (with hint)...");
  const resultA = await runCondition("A (with hint)", docPathA, true);

  // Small pause between conditions so agent subprocess fully exits
  await new Promise((r) => setTimeout(r, 1000));

  // Condition B: without hint (control)
  const docPathB = path.join(tempDir, "memo-b.md");
  await fs.writeFile(docPathB, createInitialDoc(), "utf8");
  console.error("[spike-l5] Running condition B (without hint)...");
  const resultB = await runCondition("B (without hint)", docPathB, false);

  // ============================================================================
  // Verdict
  // ============================================================================
  console.error("\n=== L5 Verdict ===");

  for (const r of [resultA, resultB]) {
    console.error(`\n--- Condition ${r.label} ---`);
    console.error(`turn1ReadCount: ${r.turn1ReadCount}`);
    console.error(`turn1WroteNewContent: ${r.turn1WroteNewContent ? "yes" : "no"}`);
    console.error(`turn2ReadCount: ${r.turn2ReadCount}`);
    console.error(`turn2SawUpdatedContent: ${r.turn2SawUpdatedContent ? "yes" : "no"}`);
    if (!r.turn1WroteNewContent) {
      console.error("WARNING: turn1 did not produce a disk write. Spike may not be testing what we think.");
    }
    if (r.turn2ContentSnippet) {
      console.error("turn2 read snippet:", JSON.stringify(r.turn2ContentSnippet.slice(0, 80)));
    }
  }

  const hintWorks = resultA.turn2ReadCount > 0 && resultA.turn2SawUpdatedContent;
  const noHintAlsoWorks = resultB.turn2ReadCount > 0 && resultB.turn2SawUpdatedContent;

  let rereadHintNecessary;
  if (hintWorks && !noHintAlsoWorks) {
    rereadHintNecessary = "yes (hint required for re-read)";
  } else if (hintWorks && noHintAlsoWorks) {
    rereadHintNecessary = "no (agent re-reads by default)";
  } else if (!hintWorks && noHintAlsoWorks) {
    rereadHintNecessary = "no (but hint may hurt — investigate)";
  } else {
    rereadHintNecessary = "inconclusive (neither condition saw updated content)";
  }

  console.error(`\nrereadHintNecessary: ${rereadHintNecessary}`);

  await fs.rm(tempDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
