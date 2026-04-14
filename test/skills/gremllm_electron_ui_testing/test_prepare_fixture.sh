#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/test/skills/gremllm_electron_ui_testing/test_helpers.sh"

SCRIPT="$ROOT/.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh"
TMP_ROOT="$(mktemp -d /tmp/gremllm-prepare-test-XXXXXX)"
file_workspace=""
folder_workspace=""
folder_workspace_without_topics=""

cleanup() {
  [[ -n "$file_workspace" ]] && rm -rf "$file_workspace"
  [[ -n "$folder_workspace" ]] && rm -rf "$folder_workspace"
  [[ -n "$folder_workspace_without_topics" ]] && rm -rf "$folder_workspace_without_topics"
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

missing_source_output="$(assert_command_fails "$SCRIPT")"
assert_contains "$missing_source_output" "Usage:"

printf '# Fixture\n\nHello world.\n' >"$TMP_ROOT/source.md"
file_workspace="$("$SCRIPT" --source "$TMP_ROOT/source.md")"
assert_path_prefix "$file_workspace" "/tmp/gremllm-"
assert_file_contains "$file_workspace/document.md" "# Fixture"
assert_dir_exists "$file_workspace/topics"

(
  cd "$TMP_ROOT"
  file_workspace="$(assert_command_fails "$SCRIPT" --source "docs/superpowers/plans/2026-04-14-gremllm-electron-ui-testing-skill.md")"
)

folder_workspace_relative="$("$SCRIPT" --source "docs/superpowers/plans/2026-04-14-gremllm-electron-ui-testing-skill.md")"
assert_path_prefix "$folder_workspace_relative" "/tmp/gremllm-"
assert_file_contains "$folder_workspace_relative/document.md" "Gremllm Electron UI Testing Skill Implementation Plan"

mkdir -p "$TMP_ROOT/source-folder/topics"
printf '# Folder Fixture\n' >"$TMP_ROOT/source-folder/document.md"
printf '{:id "topic-1"}\n' >"$TMP_ROOT/source-folder/topics/topic-1.edn"

folder_workspace="$(mktemp -d /tmp/gremllm-folder-fixture-XXXXXX)"
"$SCRIPT" --source "$TMP_ROOT/source-folder" --output "$folder_workspace" >/dev/null
assert_file_contains "$folder_workspace/document.md" "# Folder Fixture"
assert_file_contains "$folder_workspace/topics/topic-1.edn" "topic-1"

mkdir -p "$TMP_ROOT/source-folder-no-topics"
printf '# No Topics Fixture\n' >"$TMP_ROOT/source-folder-no-topics/document.md"

folder_workspace_without_topics="$(mktemp -d /tmp/gremllm-folder-fixture-no-topics-XXXXXX)"
"$SCRIPT" --source "$TMP_ROOT/source-folder-no-topics" --output "$folder_workspace_without_topics" >/dev/null
assert_file_contains "$folder_workspace_without_topics/document.md" "# No Topics Fixture"
assert_dir_exists "$folder_workspace_without_topics/topics"

invalid_output_output="$(assert_command_fails "$SCRIPT" --source "$TMP_ROOT/source.md" --output "/var/tmp/gremllm-review-invalid")"
assert_contains "$invalid_output_output" "output must live under /tmp/gremllm-*"

traversal_output="$(assert_command_fails "$SCRIPT" --source "$TMP_ROOT/source.md" --output "/tmp/gremllm-escape/../../private/tmp/gremllm-review-escaped")"
assert_contains "$traversal_output" "output must live under /tmp/gremllm-*"
