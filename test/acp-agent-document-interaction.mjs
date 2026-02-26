#!/usr/bin/env node

// ============================================================================
// ACP Spike 0: Document-First Interaction Pattern Reference
// ============================================================================
//
// This spike demonstrates the core document-first interaction pattern that
// Gremllm will use for verified knowledge work:
//
// 1. User works in a document (the artifact)
// 2. User requests AI assistance for a specific edit
// 3. Agent receives a resource_link to the document (not full content)
// 4. Agent calls readTextFile to demand-read the document
// 5. Agent proposes changes as reviewable diffs
// 6. Client captures diffs WITHOUT writing to disk (dry-run mode)
// 7. User reviews and approves/rejects proposed changes
//
// KEY RESEARCH QUESTION ANSWERED:
// Can we get reviewable diffs from the agent without the agent writing to disk?
// YES - by setting DRY_RUN=true and blocking writeTextFile, we capture proposed
// edits in tool_call_update sessionUpdates before any mutation occurs.
//
// ACP LIFECYCLE EXERCISED:
// spawn agent → initialize (capability negotiation) → newSession →
// prompt (text + resource_link) → observe sessionUpdates (streaming) → cleanup
//
// USAGE:
//   node test/acp-agent-document-interaction.mjs [document-path]
//
// CONFIGURATION (via environment variables):
//   ACP_DOC_PATH          Document to edit (overrides CLI arg)
//   ACP_AGENT_CMD         Agent command (default: npx)
//   ACP_AGENT_ARGS        Agent args (default: @zed-industries/claude-agent-acp)
//   ACP_CLIENT_FS         Filesystem caps: none, read, write, readwrite (default: readwrite)
//   VERBOSE               Set to 1 for full JSON dump of all sessionUpdates
//
// References:
// - docs/plans/2026-02-09-document-first-pivot.md
// - docs/acp-client-agent-interaction-research-2026-02-09.md

import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";
import * as acp from "@agentclientprotocol/sdk";

// ============================================================================
// Configuration
// ============================================================================

const PROJECT_ROOT = process.cwd();
const DEFAULT_DOC = path.resolve(PROJECT_ROOT, "docs/plans/2026-02-09-document-first-pivot.md");

// Document to edit - configurable via env var, CLI arg, or default
const DOC_PATH = process.env.ACP_DOC_PATH
  ? path.resolve(PROJECT_ROOT, process.env.ACP_DOC_PATH)
  : process.argv[2]
    ? path.resolve(PROJECT_ROOT, process.argv[2])
    : DEFAULT_DOC;
const DOC_URI = pathToFileURL(DOC_PATH).toString();

// Agent command - default uses npx to run Claude Code ACP
const AGENT_CMD = process.env.ACP_AGENT_CMD || "npx";
const AGENT_ARGS = process.env.ACP_AGENT_ARGS
  ? process.env.ACP_AGENT_ARGS.split(" ")
  : ["@zed-industries/claude-agent-acp"];

// Dry-run mode: Block writeTextFile to capture proposed edits without mutation
const DRY_RUN = true;

// Filesystem capabilities: none, read, write, or readwrite
const FS_MODE = (process.env.ACP_CLIENT_FS || "readwrite").toLowerCase();

// Verbose mode: Print detailed agent interaction (reasoning, tool calls, etc.)
const VERBOSE = process.env.VERBOSE === "true" || process.env.VERBOSE === "1";

// ============================================================================
// MinimalClient - ACP Client Callback Interface Implementation
// ============================================================================
//
// Implements the callbacks the ACP SDK expects from a client. This is where
// we observe agent behavior and respond to its requests.

class MinimalClient {
  constructor() {
    this.diffUpdates = [];
  }

  // sessionUpdate - The streaming observation point for all agent activity.
  // This is called repeatedly as the agent works. We handle these update types:
  //
  // - agent_message_chunk: Text the agent is streaming back to the user
  // - agent_thought_chunk: Agent's internal reasoning (thinking process)
  // - tool_call: Agent is about to invoke a tool
  // - tool_call_update: Tool execution results, including proposed diffs
  //
  // The key insight: tool_call_update with type=diff contains the proposed edit
  // BEFORE writeTextFile is called. This is our chance to capture and review.
  async sessionUpdate({ sessionId, update }) {
    const updateType = update.sessionUpdate;

    // Verbose mode: print all update types with full JSON
    if (VERBOSE) {
      console.error(`\n[${updateType}]`, JSON.stringify(update, null, 2));
      return; // Skip normal processing in verbose mode
    }

    switch (updateType) {
      case "agent_message_chunk": {
        // Stream agent's text output to stdout (user-facing response)
        if (update.content?.type === "text") {
          process.stdout.write(update.content.text);
        }
        break;
      }
      case "agent_thought_chunk": {
        // Stream agent's reasoning to stderr (thinking process)
        if (update.content?.type === "text") {
          process.stderr.write(`\x1b[2m${update.content.text}\x1b[0m`); // Dim text
        }
        break;
      }
      case "tool_call": {
        // Agent sends two tool_call updates per invocation: first with empty
        // rawInput (pending), then with actual parameters. Skip the empty one.
        if (!update.rawInput || Object.keys(update.rawInput).length === 0) break;

        const toolName = update._meta?.claudeCode?.toolName || update.kind || "unknown";
        const title = update.title || toolName;

        console.error(`\n\x1b[1m[tool_call] ${toolName}\x1b[0m - ${title}`);
        console.error(`  Input: ${JSON.stringify(update.rawInput, null, 2)}`);
        break;
      }
      case "tool_call_update": {
        // Capture diff proposals - these are the reviewable edits we want
        if (Array.isArray(update.content)) {
          const diffs = update.content.filter((item) => item.type === "diff");
          if (diffs.length > 0) {
            console.error(`\n\x1b[32m[tool_call_update] Captured ${diffs.length} diff(s)\x1b[0m (id: ${update.toolCallId})`);
            this.diffUpdates.push({
              toolCallId: update.toolCallId,
              status: update.status,
              content: diffs,
              locations: update.locations || [],
              rawOutput: update.rawOutput,
              rawUpdate: update, // Store full update for debugging
            });
          }
        }
        break;
      }
      case "available_commands_update": {
        // Agent capabilities update - usually happens at session start
        // (commands list is often empty in basic setups)
        const cmdCount = update.commands?.length || update.availableCommands?.length || 0;
        if (cmdCount > 0) {
          console.error(`\n[available_commands_update] Agent advertised ${cmdCount} commands`);
        }
        break;
      }
      default:
        console.error(`\n[${updateType}] (unhandled)`);
        break;
    }
  }

  // readTextFile - Agent calls this to fetch document content.
  // This implements the demand-read pattern: we sent a resource_link,
  // agent calls this to get the actual content. Supports line/limit for
  // partial reads (useful for large files).
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

  // writeTextFile - Agent calls this to mutate files.
  // In dry-run mode, we block this to prevent mutation. This lets us capture
  // the proposed edit (from tool_call_update) without applying it.
  // In production, this would be where we prompt the user for approval.
  async writeTextFile({ path: filePath, content }) {
    if (DRY_RUN) {
      console.error(`[dry-run] writeTextFile blocked: ${filePath} (bytes: ${content.length})`);
      return {};
    }

    await fs.writeFile(filePath, content, "utf8");
    return {};
  }

  // requestPermission - Agent calls this to ask for approval.
  // Auto-accepts by selecting the first option. In production, this would
  // present choices to the user and wait for their decision.
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

// ============================================================================
// Main - ACP Lifecycle
// ============================================================================
//
// Executes the complete ACP interaction:
// 1. Spawn the agent subprocess
// 2. Set up streaming communication (stdin/stdout as NDJSON)
// 3. Initialize with capability negotiation
// 4. Create a new session
// 5. Send prompt with text instruction + resource_link to document
// 6. Observe streaming updates (agent reads doc, proposes diff)
// 7. Output summary and cleanup

async function main() {
  // Spawn agent subprocess
  // stdio: [stdin=pipe, stdout=pipe, stderr=ignore] to silence agent's debug output
  const agentProcess = spawn(AGENT_CMD, AGENT_ARGS, {
    stdio: ["pipe", "pipe", "ignore"],
    env: { ...process.env },
  });

  agentProcess.on("error", (err) => {
    console.error(`Failed to spawn agent (${AGENT_CMD}):`, err);
    console.error("Tip: default uses `npx @zed-industries/claude-agent-acp`.");
    console.error("Override via ACP_AGENT_CMD and ACP_AGENT_ARGS if needed.");
    process.exit(1);
  });

  // Set up ACP connection over agent's stdin/stdout
  const input = Writable.toWeb(agentProcess.stdin);
  const output = Readable.toWeb(agentProcess.stdout);

  const client = new MinimalClient();
  const stream = acp.ndJsonStream(input, output);
  const connection = new acp.ClientSideConnection(() => client, stream);

  // Filesystem capability lookup - determines what file operations we expose
  const FS_CAPS = {
    none:      {},
    read:      { readTextFile: true,  writeTextFile: false },
    write:     { readTextFile: false, writeTextFile: true },
    readwrite: { readTextFile: true,  writeTextFile: true },
  };
  const fsCaps = FS_CAPS[FS_MODE] || FS_CAPS.readwrite;

  // Initialize - capability negotiation happens here
  const init = await connection.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: {
      fs: fsCaps,
      terminal: false,
    },
    clientInfo: {
      name: "gremllm-spike",
      title: "Gremllm Spike 0",
      version: "0.0.1",
    },
  });

  // Create a new session - this is where we'd configure model, tools, etc.
  const session = await connection.newSession({
    cwd: PROJECT_ROOT,
    mcpServers: [],
    model: "claude-haiku-4-5-20251001",
  });

  // Send prompt - text instruction + resource_link (not full document content)
  // The agent will call readTextFile to demand-read the document
  const promptText =
    "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else.";

  const promptResult = await connection.prompt({
    sessionId: session.sessionId,
    prompt: [
      { type: "text", text: promptText },
      { type: "resource_link", uri: DOC_URI, name: path.basename(DOC_PATH) },
    ],
  });

  // Output summary - show what we captured
  console.log("\n\n=== Summary ===");
  console.log(`Agent protocol: ${init.protocolVersion}`);
  console.log(`Stop reason: ${promptResult.stopReason}`);
  console.log(`Diff updates captured: ${client.diffUpdates.length}`);
  console.log(`Client fs mode: ${FS_MODE}`);
  console.log(`Verbose mode: ${VERBOSE ? "enabled" : "disabled (set VERBOSE=1 to enable)"}`);


  // Display captured diffs - the key proof that we got reviewable edits
  // Structure: diffUpdates[].content[].{path, oldText, newText, type: "diff"}
  // To parse: iterate content array, compare oldText vs newText line-by-line
  if (client.diffUpdates.length > 0) {
    console.log("\n--- Captured Diffs ---");
    console.log(JSON.stringify(client.diffUpdates, null, 2));
  }

  agentProcess.kill();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});


// ============================================================================
// OUTPUT
// ============================================================================
//
// $ node test/acp-agent-document-interaction.mjs
//
// [available_commands_update] Agent advertised 30 commands
// The user wants me to:
// 1. Read the linked document at `/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md`
// 2. Update the front-matter Date field to 2026-02-10
// 3. Don't change anything else
//
// Let me read the file first to see its contents.I'll read the document and update the Date field in the front-matter.
// [tool_call] mcp__acp__Read - Read /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md
//   Input: {
//   "file_path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md"
// }
// Now I need to update the Date field in the front-matter from `2026-02-09` to `2026-02-10`. The front-matter is at the top of the file:
//
// ```markdown
// **Date:** 2026-02-09 (revised)
// ```
//
// I'll use the Edit tool to change this to:
//
// ```markdown
// **Date:** 2026-02-10 (revised)
// ```
//
// I should make sure to keep the "(revised)" part and only change the date.
// [tool_call] mcp__acp__Edit - Edit `/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md`
//   Input: {
//   "file_path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md",
//   "old_string": "**Date:** 2026-02-09 (revised)",
//   "new_string": "**Date:** 2026-02-10 (revised)"
// }
// [dry-run] writeTextFile blocked: /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md (bytes: 13646)
//
// [tool_call_update] Captured 1 diff(s) (id: toolu_01LccXqJjxpfBLwXBy5K9CLR)
// Perfect, the edit has been applied. The Date field is now updated to 2026-02-10 while keeping everything else, including the "(revised)" suffix, intact.Done. Updated the Date field from 2026-02-09 to 2026-02-10.
//
// === Summary ===
// Agent protocol: 1
// Stop reason: end_turn
// Diff updates captured: 1
// Client fs mode: readwrite
// Verbose mode: disabled (set VERBOSE=1 to enable)
//
// --- Captured Diffs ---
// [
//   {
//     "toolCallId": "toolu_01LccXqJjxpfBLwXBy5K9CLR",
//     "status": "completed",
//     "content": [
//       {
//         "newText": "# Document-First Pivot — Master Architectural Plan\n\n**Date:** 2026-02-10 (revised)\n**References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n\n## Context\n",
//         "oldText": "# Document-First Pivot — Master Architectural Plan\n\n**Date:** 2026-02-09 (revised)\n**References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n\n## Context\n",
//         "path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md",
//         "type": "diff"
//       }
//     ],
//     "locations": [
//       {
//         "line": 1,
//         "path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md"
//       }
//     ],
//     "rawOutput": [
//       {
//         "type": "text",
//         "text": "Index: /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n===================================================================\n--- /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n+++ /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n@@ -1,7 +1,7 @@\n # Document-First Pivot — Master Architectural Plan\n \n-**Date:** 2026-02-09 (revised)\n+**Date:** 2026-02-10 (revised)\n **References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n \n ## Context\n \n"
//       }
//     ],
//     "rawUpdate": {
//       "_meta": {
//         "claudeCode": {
//           "toolName": "mcp__acp__Edit"
//         }
//       },
//       "content": [
//         {
//           "newText": "# Document-First Pivot — Master Architectural Plan\n\n**Date:** 2026-02-10 (revised)\n**References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n\n## Context\n",
//           "oldText": "# Document-First Pivot — Master Architectural Plan\n\n**Date:** 2026-02-09 (revised)\n**References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n\n## Context\n",
//           "path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md",
//           "type": "diff"
//         }
//       ],
//       "locations": [
//         {
//           "line": 1,
//           "path": "/Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md"
//         }
//       ],
//       "rawOutput": [
//         {
//           "type": "text",
//           "text": "Index: /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n===================================================================\n--- /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n+++ /Users/paul/Projects/gremllm/docs/plans/2026-02-09-document-first-pivot.md\n@@ -1,7 +1,7 @@\n # Document-First Pivot — Master Architectural Plan\n \n-**Date:** 2026-02-09 (revised)\n+**Date:** 2026-02-10 (revised)\n **References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`\n \n ## Context\n \n"
//         }
//       ],
//       "status": "completed",
//       "toolCallId": "toolu_01LccXqJjxpfBLwXBy5K9CLR",
//       "sessionUpdate": "tool_call_update"
//     }
//   }
// ]
