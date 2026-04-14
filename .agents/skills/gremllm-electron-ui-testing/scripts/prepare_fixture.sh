#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"

usage() {
  cat <<'EOF' >&2
Usage: prepare_fixture.sh --source <markdown-file-or-workspace-dir> [--output /tmp/gremllm-...]
EOF
  exit 1
}

resolve_source() {
  local source_path="$1"
  if [[ -e "$source_path" ]]; then
    printf '%s\n' "$source_path"
    return 0
  fi

  if [[ -e "$REPO_ROOT/$source_path" ]]; then
    printf '%s\n' "$REPO_ROOT/$source_path"
    return 0
  fi

  echo "prepare_fixture.sh: source not found: $source_path" >&2
  exit 1
}

ensure_tmp_output() {
  local output_path="$1"
  case "$output_path" in
    /tmp/gremllm-*) ;;
    *)
      echo "prepare_fixture.sh: output must live under /tmp/gremllm-*: $output_path" >&2
      exit 1
      ;;
  esac

  case "$output_path" in
    ../*|*/../*|*/..)
      echo "prepare_fixture.sh: output must live under /tmp/gremllm-*: $output_path" >&2
      exit 1
      ;;
  esac
}

copy_source() {
  local source_path="$1"
  local output_path="$2"

  if [[ -d "$source_path" ]]; then
    /bin/cp -R "$source_path"/. "$output_path"
  else
    mkdir -p "$output_path"
    /bin/cp "$source_path" "$output_path/document.md"
  fi

  mkdir -p "$output_path/topics"
}

source_arg=""
output_arg=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      source_arg="${2:-}"
      shift 2
      ;;
    --output)
      output_arg="${2:-}"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ -n "$source_arg" ]] || usage

source_path="$(resolve_source "$source_arg")"

if [[ -n "$output_arg" ]]; then
  ensure_tmp_output "$output_arg"
  rm -rf "$output_arg"
  mkdir -p "$output_arg"
  output_path="$output_arg"
else
  output_path="$(mktemp -d /tmp/gremllm-fixture-XXXXXX)"
fi

copy_source "$source_path" "$output_path"
printf '%s\n' "$output_path"
