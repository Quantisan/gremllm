#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  if [[ "$expected" != "$actual" ]]; then
    fail "expected [$expected] but got [$actual]"
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail "expected [$haystack] to contain [$needle]"
  fi
}

assert_path_prefix() {
  local path="$1"
  local prefix="$2"
  if [[ "$path" != "$prefix"* ]]; then
    fail "expected path [$path] to start with [$prefix]"
  fi
}

assert_file_exists() {
  local path="$1"
  [[ -f "$path" ]] || fail "expected file [$path] to exist"
}

assert_dir_exists() {
  local path="$1"
  [[ -d "$path" ]] || fail "expected directory [$path] to exist"
}

assert_file_contains() {
  local path="$1"
  local needle="$2"
  assert_file_exists "$path"
  grep -Fq -- "$needle" "$path" || fail "expected file [$path] to contain [$needle]"
}

assert_command_fails() {
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    fail "expected command to fail: $*"
  fi
  printf '%s\n' "$output"
}
