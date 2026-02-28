#!/usr/bin/env node

import assert from "node:assert/strict";
import path from "node:path";
import acpModule from "../resources/acp/permission.js";

const { makeResolver } = acpModule;
const { normalizePath, isWithinRoot, requestedPath, requestedToolName } = acpModule.__test__;

assert.equal(typeof makeResolver, "function", "Expected makeResolver export");
assert.equal(typeof normalizePath, "function", "Expected __test__.normalizePath export");
assert.equal(typeof isWithinRoot, "function", "Expected __test__.isWithinRoot export");
assert.equal(typeof requestedPath, "function", "Expected __test__.requestedPath export");
assert.equal(typeof requestedToolName, "function", "Expected __test__.requestedToolName export");

function option(optionId, kind) {
  return { optionId, kind, name: kind };
}

function selectedOptionId(result) {
  assert.equal(result.outcome.outcome, "selected");
  return result.outcome.optionId;
}

const fullOptions = [
  option("allow-always", "allow_always"),
  option("allow-once", "allow_once"),
  option("reject-once", "reject_once"),
  option("reject-always", "reject_always")
];

const cwd = path.resolve(process.cwd(), "resources");
const fileInCwd = path.resolve(cwd, "gremllm-launch-log.md");
const fileOutsideCwd = path.resolve(process.cwd(), "README.md");

const getSessionCwd = (sessionId) => (sessionId === "session-known" ? cwd : undefined);
const resolver = makeResolver(getSessionCwd);

// ACP read always allowed (rawInput.path)
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", toolName: "mcp__acp__Read", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "ACP read via rawInput.path is allowed");
}

// plain Read is rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", toolName: "Read", rawInput: { file_path: fileOutsideCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "plain Read is rejected");
}

// ACP edit within cwd: allowed
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", toolName: "mcp__acp__Edit", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "ACP edit within cwd is allowed");
}

// ACP write within cwd: allowed
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", toolName: "mcp__acp__Write", rawInput: { file_path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "ACP write within cwd is allowed");
}

// plain Edit is rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", toolName: "Edit", rawInput: { file_path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "plain Edit is rejected");
}

// ACP edit outside cwd: rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", toolName: "mcp__acp__Edit", rawInput: { file_path: fileOutsideCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "ACP edit outside cwd is rejected");
}

// ACP edit without path metadata: rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", toolName: "mcp__acp__Edit", rawInput: { replace_all: false } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "ACP edit without path metadata is rejected");
}

// no options: cancelled
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", toolName: "mcp__acp__Read", rawInput: { path: fileInCwd } },
    options: []
  });
  assert.deepEqual(result, { outcome: { outcome: "cancelled" } }, "no options yields cancelled");
}

// unknown/missing session: ACP edit rejected
{
  const result = resolver({
    sessionId: "session-unknown",
    toolCall: { kind: "edit", toolName: "mcp__acp__Edit", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "ACP edit with unknown session is rejected");
}

console.log("acp-permission-policy: all assertions passed");
