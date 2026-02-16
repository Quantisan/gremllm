// resources/acp/permission.js

function selectOptionByKind(options, preferredKinds, fallback) {
  for (const kind of preferredKinds) {
    const match = options.find((option) => option?.kind === kind);
    if (match) return match;
  }

  return fallback(options);
}

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
  __test__: {
    resolvePermissionOutcome
  }
};
