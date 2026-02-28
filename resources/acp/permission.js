// resources/acp/permission.js
const path = require("node:path");

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

function requestedToolName(toolCall) {
  if (typeof toolCall?.toolName === "string" && toolCall.toolName.length > 0) {
    return toolCall.toolName;
  }

  const metaToolName = toolCall?._meta?.claudeCode?.toolName;
  return typeof metaToolName === "string" && metaToolName.length > 0 ? metaToolName : null;
}

function selectOptionByKind(options, preferredKinds, fallback) {
  for (const kind of preferredKinds) {
    const match = options.find((option) => option?.kind === kind);
    if (match) return match;
  }

  return fallback(options);
}

function makeResolver(getSessionCwd) {
  return function resolvePermission(params) {
    const options = Array.isArray(params?.options) ? params.options : [];
    const toolCall = params?.toolCall ?? {};
    const toolKind = toolCall.kind ?? null;

    if (options.length === 0) {
      return { outcome: { outcome: "cancelled" } };
    }

    // TODO(security): Do not auto-approve all "read" tool calls.
    // Restrict reads to an allowlist (for example, workspace root and explicitly linked files);
    // otherwise reject/cancel to avoid exposing sensitive local files.
    if (toolKind === "read") {
      const approveOption = selectOptionByKind(
        options,
        ["allow_always", "allow_once"],
        (list) => list[0]
      );
      return { outcome: { outcome: "selected", optionId: approveOption.optionId } };
    }

    if (toolKind === "edit") {
      const cwd = getSessionCwd(params.sessionId);
      const normalizedCwd = normalizePath(cwd);
      const normalizedRequested = normalizePath(requestedPath(toolCall));
      if (normalizedCwd && normalizedRequested && isWithinRoot(normalizedRequested, normalizedCwd)) {
        const approveOption = selectOptionByKind(
          options,
          ["allow_always", "allow_once"],
          (list) => list[0]
        );
        return { outcome: { outcome: "selected", optionId: approveOption.optionId } };
      }
    }

    const rejectOption = selectOptionByKind(
      options,
      ["reject_once", "reject_always"],
      (list) => list[list.length - 1]
    );
    return { outcome: { outcome: "selected", optionId: rejectOption.optionId } };
  };
}

// TODO: integration tests
function resolvePermissionOutcome(params) {
  const options = Array.isArray(params?.options) ? params.options : [];
  const toolKind = params?.toolCall?.kind || null;

  if (options.length === 0) {
    return { outcome: { outcome: "cancelled" } };
  }

  // TODO(security): Do not auto-approve all "read" tool calls.
  // Restrict reads to an allowlist (for example, workspace root and explicitly linked files);
  // otherwise reject/cancel to avoid exposing sensitive local files.
  if (toolKind === "read") {
    const approveOption = selectOptionByKind(
      options,
      ["allow_once", "allow_always"],
      (list) => list[0]
    );
    return {
      outcome: {
        outcome: "selected",
        optionId: approveOption.optionId
      }
    };
  }

  const rejectOption = selectOptionByKind(
    options,
    ["reject_once", "reject_always"],
    (list) => list[list.length - 1]
  );
  return {
    outcome: {
      outcome: "selected",
      optionId: rejectOption.optionId
    }
  };
}

module.exports = {
  resolvePermissionOutcome,
  makeResolver,
  __test__: {
    resolvePermissionOutcome,
    normalizePath,
    isWithinRoot,
    requestedPath,
    requestedToolName
  }
};
