#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/test/skills/gremllm_electron_ui_testing/test_helpers.sh"

SCRIPT="$ROOT/.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh"
TMP_ROOT="$(mktemp -d /tmp/gremllm-prepare-test-XXXXXX)"
file_workspace=""
folder_workspace=""

cleanup() {
  [[ -n "$file_workspace" ]] && rm -rf "$file_workspace"
  [[ -n "$folder_workspace" ]] && rm -rf "$folder_workspace"
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

printf '# Fixture\n\nHello world.\n' >"$TMP_ROOT/source.md"
file_workspace="$("$SCRIPT" --source "$TMP_ROOT/source.md")"
assert_path_prefix "$file_workspace" "/tmp/gremllm-"
assert_file_contains "$file_workspace/document.md" "# Fixture"
assert_dir_exists "$file_workspace/topics"

mkdir -p "$TMP_ROOT/source-folder/topics"
printf '# Folder Fixture\n' >"$TMP_ROOT/source-folder/document.md"
printf '{:id "topic-1"}\n' >"$TMP_ROOT/source-folder/topics/topic-1.edn"

folder_workspace="$(mktemp -d /tmp/gremllm-folder-fixture-XXXXXX)"
"$SCRIPT" --source "$TMP_ROOT/source-folder" --output "$folder_workspace" >/dev/null
assert_file_contains "$folder_workspace/document.md" "# Folder Fixture"
assert_file_contains "$folder_workspace/topics/topic-1.edn" "topic-1"
