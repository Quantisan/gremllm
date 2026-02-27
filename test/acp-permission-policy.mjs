#!/usr/bin/env node

import assert from "node:assert/strict";
import path from "node:path";
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

function selectOptionByKind(options, preferredKinds, fallback) {
  for (const kind of preferredKinds) {
    const match = options.find((entry) => entry?.kind === kind);
    if (match) return match;
  }
  return fallback(options);
}

function normalizePath(value) {
  if (typeof value !== "string" || value.length === 0) return null;
  return path.resolve(value);
}

function isWithinRoot(candidatePath, rootPath) {
  const relative = path.relative(rootPath, candidatePath);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function requestedPath(toolCall) {
  const rawInput = toolCall?.rawInput ?? {};
  return rawInput.path ?? rawInput.file_path ?? null;
}

function isAllowedEditPath(toolCall, policy) {
  const normalizedRequested = normalizePath(requestedPath(toolCall));
  if (!normalizedRequested) return false;

  const normalizedWorkspaceRoot = normalizePath(policy.workspaceRoot);
  if (normalizedWorkspaceRoot && isWithinRoot(normalizedRequested, normalizedWorkspaceRoot)) {
    return true;
  }

  const linkedFiles = new Set((policy.linkedFiles ?? []).map(normalizePath).filter(Boolean));
  return linkedFiles.has(normalizedRequested);
}

function resolvePermissionOutcomeSpike(params, policy) {
  const options = Array.isArray(params?.options) ? params.options : [];
  const toolCall = params?.toolCall ?? {};
  const toolKind = toolCall.kind ?? null;

  if (options.length === 0) {
    return { outcome: { outcome: "cancelled" } };
  }

  if (toolKind === "read") {
    const allowOption = selectOptionByKind(options, ["allow_always", "allow_once"], (list) => list[0]);
    return { outcome: { outcome: "selected", optionId: allowOption.optionId } };
  }

  if (toolKind === "edit" && isAllowedEditPath(toolCall, policy)) {
    const allowOption = selectOptionByKind(options, ["allow_always", "allow_once"], (list) => list[0]);
    return { outcome: { outcome: "selected", optionId: allowOption.optionId } };
  }

  const rejectOption = selectOptionByKind(options, ["reject_once", "reject_always"], (list) => list[list.length - 1]);
  return { outcome: { outcome: "selected", optionId: rejectOption.optionId } };
}

function printSpikeCase(label, params, baselineOutcome, spikeOutcome) {
  console.log(`\n=== ${label} ===`);
  console.log("[params]");
  console.dir(params, { depth: null });
  console.log("[selected]");
  console.log({
    baselineOptionId: baselineOutcome.outcome?.outcome === "selected" ? baselineOutcome.outcome.optionId : "cancelled",
    spikeOptionId: spikeOutcome.outcome?.outcome === "selected" ? spikeOutcome.outcome.optionId : "cancelled"
  });
}

function runSpikeCase({ label, params, expectedSpikeOptionId, policy }) {
  const baselineOutcome = resolvePermissionOutcome(params);
  const spikeOutcome = resolvePermissionOutcomeSpike(params, policy);

  printSpikeCase(label, params, baselineOutcome, spikeOutcome);
  assert.equal(selectedOptionId(spikeOutcome), expectedSpikeOptionId);
}

const workspaceRoot = path.resolve(process.cwd(), "resources");
const linkedDocument = path.resolve(process.cwd(), "resources/gremllm-launch-log.md");
const outsideFile = path.resolve(process.cwd(), "README.md");

const fullOptions = [
  option("allow-always", "allow_always"),
  option("allow-once", "allow_once"),
  option("reject-once", "reject_once"),
  option("reject-always", "reject_always")
];

const policy = {
  workspaceRoot,
  linkedFiles: [linkedDocument]
};

runSpikeCase({
  label: "read uses rawInput.path and always allows",
  params: {
    sessionId: "session-1",
    toolCall: {
      toolCallId: "tool-1",
      kind: "read",
      title: `Read ${linkedDocument}`,
      rawInput: { path: linkedDocument }
    },
    options: fullOptions
  },
  expectedSpikeOptionId: "allow-always",
  policy
});

runSpikeCase({
  label: "read uses rawInput.file_path and always allows",
  params: {
    sessionId: "session-2",
    toolCall: {
      toolCallId: "tool-2",
      kind: "read",
      title: `Read ${outsideFile}`,
      rawInput: { file_path: outsideFile },
      locations: [{ path: outsideFile }]
    },
    options: fullOptions
  },
  expectedSpikeOptionId: "allow-always",
  policy
});

runSpikeCase({
  label: "edit in workspace root is conditionally allowed",
  params: {
    sessionId: "session-3",
    toolCall: {
      toolCallId: "tool-3",
      kind: "edit",
      title: `Edit ${linkedDocument}`,
      rawInput: { path: linkedDocument }
    },
    options: fullOptions
  },
  expectedSpikeOptionId: "allow-always",
  policy
});

runSpikeCase({
  label: "edit outside workspace is rejected",
  params: {
    sessionId: "session-4",
    toolCall: {
      toolCallId: "tool-4",
      kind: "edit",
      title: `Edit ${outsideFile}`,
      rawInput: { file_path: outsideFile }
    },
    options: fullOptions
  },
  expectedSpikeOptionId: "reject-once",
  policy
});

runSpikeCase({
  label: "edit without path metadata is rejected",
  params: {
    sessionId: "session-5",
    toolCall: {
      toolCallId: "tool-5",
      kind: "edit",
      title: "Edit without rawInput path",
      rawInput: { replace_all: false }
    },
    options: fullOptions
  },
  expectedSpikeOptionId: "reject-once",
  policy
});

{
  const result = resolvePermissionOutcomeSpike({
    sessionId: "session-6",
    toolCall: { kind: "read", title: "Read with no options", rawInput: { path: linkedDocument } },
    options: []
  }, policy);
  console.log("\n=== no options ===");
  console.dir(result, { depth: null });
  assert.deepEqual(result, { outcome: { outcome: "cancelled" } });
}

console.log("\nacp-permission-policy spike: all assertions passed");
