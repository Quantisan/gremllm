#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/test/skills/gremllm_electron_ui_testing/test_helpers.sh"

TMP_ROOT="$(mktemp -d /tmp/gremllm-applescript-test-XXXXXX)"
trap 'rm -rf "$TMP_ROOT"' EXIT

FOCUS_SCRIPT="$ROOT/.agents/skills/gremllm-electron-ui-testing/scripts/focus_app.applescript"
OPEN_SCRIPT="$ROOT/.agents/skills/gremllm-electron-ui-testing/scripts/open_workspace.applescript"

osacompile -o "$TMP_ROOT/focus_app.scpt" "$FOCUS_SCRIPT" >/dev/null
assert_file_exists "$TMP_ROOT/focus_app.scpt"

osacompile -o "$TMP_ROOT/open_workspace.scpt" "$OPEN_SCRIPT" >/dev/null
assert_file_exists "$TMP_ROOT/open_workspace.scpt"
