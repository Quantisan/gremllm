#!/usr/bin/env node

import assert from "node:assert/strict";
import path from "node:path";
import acpModule from "../resources/acp/permission.js";

const { makeResolver } = acpModule;
const { normalizePath, isWithinRoot, requestedPath } = acpModule.__test__;

assert.equal(typeof makeResolver, "function", "Expected makeResolver export");
assert.equal(typeof normalizePath, "function", "Expected __test__.normalizePath export");
assert.equal(typeof isWithinRoot, "function", "Expected __test__.isWithinRoot export");
assert.equal(typeof requestedPath, "function", "Expected __test__.requestedPath export");

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

// read always allowed (rawInput.path)
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "read via rawInput.path always allowed");
}

// read always allowed (rawInput.file_path, outside cwd)
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", rawInput: { file_path: fileOutsideCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "read via rawInput.file_path always allowed");
}

// edit within cwd: allowed
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "allow-always", "edit within cwd is allowed");
}

// edit outside cwd: rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", rawInput: { file_path: fileOutsideCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "edit outside cwd is rejected");
}

// edit without path metadata: rejected
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "edit", rawInput: { replace_all: false } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "edit without path metadata is rejected");
}

// no options: cancelled
{
  const result = resolver({
    sessionId: "session-known",
    toolCall: { kind: "read", rawInput: { path: fileInCwd } },
    options: []
  });
  assert.deepEqual(result, { outcome: { outcome: "cancelled" } }, "no options yields cancelled");
}

// unknown/missing session: edit rejected
{
  const result = resolver({
    sessionId: "session-unknown",
    toolCall: { kind: "edit", rawInput: { path: fileInCwd } },
    options: fullOptions
  });
  assert.equal(selectedOptionId(result), "reject-once", "edit with unknown session is rejected");
}

console.log("acp-permission-policy: all assertions passed");
