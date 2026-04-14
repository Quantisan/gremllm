#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
CDP_PORT="${GREMLLM_UI_TEST_PORT:-9222}"
TARGET_TITLE="${GREMLLM_UI_TEST_TARGET_TITLE:-Gremllm}"
ELECTRON_BIN="${GREMLLM_UI_TEST_ELECTRON_BIN:-$REPO_ROOT/node_modules/.bin/electron}"
LAUNCH_LOG="${GREMLLM_UI_TEST_LAUNCH_LOG:-/tmp/gremllm-electron-ui-testing-launch.log}"
WAIT_SECONDS="${GREMLLM_UI_TEST_WAIT_SECONDS:-20}"

fetch_target() {
  node -e '
const [port, title] = process.argv.slice(1);
const url = `http://127.0.0.1:${port}/json/list`;
fetch(url)
  .then(async (response) => {
    if (!response.ok) process.exit(2);
    const targets = await response.json();
    const target = targets.find((item) => item.type === "page" && item.title === title);
    if (!target) process.exit(3);
    process.stdout.write(JSON.stringify(target));
  })
  .catch(() => process.exit(4));
' "$CDP_PORT" "$TARGET_TITLE"
}

print_result() {
  local status="$1"
  local pid_value="$2"
  local target_json="$3"

  node -e '
const [status, port, pidValue, targetJson] = process.argv.slice(1);
const target = JSON.parse(targetJson);
const payload = {
  status,
  port: Number(port),
  title: target.title,
  webSocketDebuggerUrl: target.webSocketDebuggerUrl
};
if (pidValue !== "null") {
  payload.pid = Number(pidValue);
}
process.stdout.write(JSON.stringify(payload));
' "$status" "$CDP_PORT" "$pid_value" "$target_json"
}

wait_for_target() {
  local deadline=$((SECONDS + WAIT_SECONDS))
  local target_json=""

  while (( SECONDS < deadline )); do
    if target_json="$(fetch_target 2>/dev/null)"; then
      printf '%s\n' "$target_json"
      return 0
    fi
    sleep 1
  done

  return 1
}

if target_json="$(fetch_target 2>/dev/null)"; then
  print_result "attached" "null" "$target_json"
  exit 0
fi

if [[ ! -x "$ELECTRON_BIN" ]]; then
  echo "launch_app.sh: electron binary not executable at $ELECTRON_BIN" >&2
  exit 1
fi

if [[ "${GREMLLM_UI_TEST_SKIP_BUILD:-0}" != "1" ]]; then
  (
    cd "$REPO_ROOT"
    npm run build
  ) >/dev/null
fi

rm -f "$LAUNCH_LOG"
(
  cd "$REPO_ROOT"
  "$ELECTRON_BIN" . "--remote-debugging-port=${CDP_PORT}"
) >"$LAUNCH_LOG" 2>&1 &
app_pid="$!"

if ! target_json="$(wait_for_target)"; then
  echo "launch_app.sh: timed out waiting for renderer target [$TARGET_TITLE] on port [$CDP_PORT]" >&2
  echo "launch_app.sh: launch log: $LAUNCH_LOG" >&2
  exit 1
fi

print_result "launched" "$app_pid" "$target_json"
