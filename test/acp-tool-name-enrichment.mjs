#!/usr/bin/env node

import assert from "node:assert/strict";
import acpModule from "../resources/acp/index.js";

const { rememberToolName, enrichPermissionParams } = acpModule.__test__;

assert.equal(typeof rememberToolName, "function", "Expected __test__.rememberToolName export");
assert.equal(typeof enrichPermissionParams, "function", "Expected __test__.enrichPermissionParams export");

{
  const toolNames = new Map();

  rememberToolName(toolNames, {
    update: {
      sessionUpdate: "tool_call",
      toolCallId: "toolu_01",
      _meta: { claudeCode: { toolName: "mcp__acp__Edit" } }
    }
  });

  const enriched = enrichPermissionParams(toolNames, {
    sessionId: "session-1",
    toolCall: {
      toolCallId: "toolu_01",
      title: "Edit `/tmp/test.md`",
      rawInput: { file_path: "/tmp/test.md" }
    },
    options: []
  });

  assert.equal(
    enriched.toolCall.toolName,
    "mcp__acp__Edit",
    "permission payload should include tracked tool name"
  );
}

{
  const toolNames = new Map();
  const original = {
    sessionId: "session-1",
    toolCall: {
      toolCallId: "toolu_missing",
      title: "Edit `/tmp/test.md`",
      rawInput: { file_path: "/tmp/test.md" }
    },
    options: []
  };

  const enriched = enrichPermissionParams(toolNames, original);
  assert.equal(
    enriched.toolCall.toolName,
    undefined,
    "permission payload should remain unchanged when no tool name is known"
  );
}

console.log("acp-tool-name-enrichment: all assertions passed");
