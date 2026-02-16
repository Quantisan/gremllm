#!/usr/bin/env node

import assert from "node:assert/strict";
import acpModule from "../resources/acp/permission.js";

const resolvePermissionOutcome = acpModule.__test__?.resolvePermissionOutcome;

assert.equal(
  typeof resolvePermissionOutcome,
  "function",
  "Expected __test__.resolvePermissionOutcome export"
);

function selectedOptionId(result) {
  assert.equal(result.outcome.outcome, "selected");
  return result.outcome.optionId;
}

function option(optionId, kind, name = kind) {
  return { optionId, kind, name };
}

{
  const options = [
    option("allow-always", "allow_always"),
    option("allow-once", "allow_once"),
    option("reject-once", "reject_once")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "read", title: "Read /tmp/file.txt" },
    options
  });
  assert.equal(selectedOptionId(result), "allow-once");
}

{
  const options = [
    option("reject-always", "reject_always"),
    option("allow-always", "allow_always")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "read", title: "Read" },
    options
  });
  assert.equal(selectedOptionId(result), "allow-always");
}

{
  const options = [
    option("fallback-first", "reject_always"),
    option("fallback-second", "reject_once")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "read", title: "Read" },
    options
  });
  assert.equal(selectedOptionId(result), "fallback-first");
}

{
  const options = [
    option("reject-always", "reject_always"),
    option("allow-once", "allow_once"),
    option("reject-once", "reject_once")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "edit", title: "Edit /tmp/file.txt" },
    options
  });
  assert.equal(selectedOptionId(result), "reject-once");
}

{
  const options = [
    option("allow-once", "allow_once"),
    option("reject-always", "reject_always")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { title: "Read /tmp/file.txt" },
    options
  });
  assert.equal(selectedOptionId(result), "reject-always");
}

{
  const result = resolvePermissionOutcome({
    toolCall: { kind: "read", title: "Read /tmp/file.txt" },
    options: []
  });
  assert.deepEqual(result, { outcome: { outcome: "cancelled" } });
}

{
  const options = [
    option("reject-once", "reject_once"),
    option("allow-once", "allow_once")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "edit", title: "Read file" },
    options
  });
  assert.equal(selectedOptionId(result), "reject-once");
}

{
  const options = [
    option("reject-once", "reject_once"),
    option("allow-once", "allow_once")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "read", title: "Fetch metadata" },
    options
  });
  assert.equal(selectedOptionId(result), "allow-once");
}

{
  const options = [
    option("reject-once", "reject_once"),
    option("reject-always", "reject_always")
  ];
  const result = resolvePermissionOutcome({
    toolCall: { kind: "edit", title: "Edit file" },
    options
  });
  const optionId = selectedOptionId(result);
  assert.equal(typeof optionId, "string");
  assert.ok(options.some((entry) => entry.optionId === optionId));
}

console.log("acp-permission-policy: all tests passed");
