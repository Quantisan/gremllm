#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/test/skills/gremllm_electron_ui_testing/test_helpers.sh"

SCRIPT="$ROOT/.agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh"
TMP_ROOT="$(mktemp -d /tmp/gremllm-launch-test-XXXXXX)"
ATTACH_PID=""

cleanup() {
  [[ -n "$ATTACH_PID" ]] && kill "$ATTACH_PID" >/dev/null 2>&1 || true
  if [[ -f "$TMP_ROOT/launched-server.pid" ]]; then
    kill "$(cat "$TMP_ROOT/launched-server.pid")" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

mkdir -p "$TMP_ROOT/bin"

cat >"$TMP_ROOT/bin/npm" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" >> "$TMP_ROOT/npm.log"
exit 0
EOF
chmod +x "$TMP_ROOT/bin/npm"

cat >"$TMP_ROOT/bin/electron" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" >> "$TMP_ROOT/electron.log"
port=""
for arg in "\$@"; do
  case "\$arg" in
    --remote-debugging-port=*) port="\${arg#*=}" ;;
  esac
done
node -e '
const fs = require("node:fs");
const http = require("node:http");
const port = Number(process.argv[1]);
const pidFile = process.argv[2];
const server = http.createServer((req, res) => {
  if (req.url === "/json/list") {
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify([{id: "page-1", type: "page", title: "Gremllm", webSocketDebuggerUrl: "ws://127.0.0.1:" + port + "/devtools/page/1"}]));
    return;
  }
  res.statusCode = 404;
  res.end("not found");
});
server.listen(port, "127.0.0.1", () => fs.writeFileSync(pidFile, String(process.pid)));
process.on("SIGTERM", () => server.close(() => process.exit(0)));
setInterval(() => {}, 1000);
' "\$port" "$TMP_ROOT/launched-server.pid"
EOF
chmod +x "$TMP_ROOT/bin/electron"

ATTACH_PORT=9334
node -e '
const http = require("node:http");
const port = Number(process.argv[1]);
const server = http.createServer((req, res) => {
  if (req.url === "/json/list") {
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify([{id: "attached-page", type: "page", title: "Gremllm", webSocketDebuggerUrl: `ws://127.0.0.1:\${port}/devtools/page/attached`}]));
    return;
  }
  res.statusCode = 404;
  res.end("not found");
});
server.listen(port, "127.0.0.1");
process.on("SIGTERM", () => server.close(() => process.exit(0)));
setInterval(() => {}, 1000);
' "$ATTACH_PORT" &
ATTACH_PID="$!"
sleep 1

attach_output="$(PATH="$TMP_ROOT/bin:$PATH" GREMLLM_UI_TEST_PORT="$ATTACH_PORT" GREMLLM_UI_TEST_ELECTRON_BIN="$TMP_ROOT/bin/electron" "$SCRIPT")"
assert_contains "$attach_output" '"status":"attached"'
assert_contains "$attach_output" '"port":9334'
[[ ! -f "$TMP_ROOT/electron.log" ]] || fail "electron should not run for attach case"
[[ ! -f "$TMP_ROOT/npm.log" ]] || fail "npm should not run for attach case"

kill "$ATTACH_PID"
ATTACH_PID=""

LAUNCH_PORT=9335
launch_output="$(PATH="$TMP_ROOT/bin:$PATH" GREMLLM_UI_TEST_PORT="$LAUNCH_PORT" GREMLLM_UI_TEST_ELECTRON_BIN="$TMP_ROOT/bin/electron" "$SCRIPT")"
assert_contains "$launch_output" '"status":"launched"'
assert_contains "$launch_output" '"port":9335'
assert_file_contains "$TMP_ROOT/npm.log" "run build"
assert_file_contains "$TMP_ROOT/electron.log" "--remote-debugging-port=9335"
assert_file_exists "$TMP_ROOT/launched-server.pid"
