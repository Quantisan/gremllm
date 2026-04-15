# Gremllm Electron UI Testing Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repo-local Codex skill that can validate the live `gremllm` Electron app through disposable fixtures, deterministic launch-or-attach behavior, repo-specific macOS UI control, CDP inspection, and structured evidence capture.

**Architecture:** Keep the whole feature repo-local under `.agents/skills/gremllm-electron-ui-testing/`. Put repo facts in one compact reference file, keep each fragile mechanic in its own shell/AppleScript/Node helper, and verify them with focused shell and `node:test` coverage plus one real validation session against the running app. Do not add dependencies or broad abstractions; use Bash for process/workspace control, AppleScript for macOS GUI control, and Node's built-in `fetch` and `WebSocket` for CDP.

**Tech Stack:** Bash, AppleScript, Node.js 25, Electron, Chromium DevTools Protocol, `node:test`, macOS

---

## File Map

- Create: `.agents/skills/gremllm-electron-ui-testing/SKILL.md`
  Purpose: Repo-local skill instructions, safety posture, workflow, failure handling, and one worked example.
- Create: `.agents/skills/gremllm-electron-ui-testing/references/repo-facts.md`
  Purpose: Compact source of hardcoded repo facts used by the skill and reflected in helper defaults.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh`
  Purpose: Create disposable workspace fixtures under `/tmp/gremllm-*` from a markdown file or workspace folder.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh`
  Purpose: Attach to an existing CDP-enabled app when possible or build + launch Electron with remote debugging on port `9222`.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/focus_app.applescript`
  Purpose: Bring the correct macOS app window to the foreground.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/open_workspace.applescript`
  Purpose: Open the workspace picker, enter a target path, and confirm the workspace open flow.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js`
  Purpose: Connect to the `Gremllm` renderer target via CDP and evaluate arbitrary expressions with normalized JSON output.
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js`
  Purpose: Provide higher-level DOM, selection, and console capture helpers on top of raw CDP evaluation.
- Create: `test/skills/gremllm_electron_ui_testing/test_helpers.sh`
  Purpose: Small shared shell assertions for the repo-local skill tests.
- Create: `test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh`
  Purpose: Verify disposable fixture creation for markdown-file and workspace-folder sources.
- Create: `test/skills/gremllm_electron_ui_testing/test_launch_app.sh`
  Purpose: Verify attach-first behavior and deterministic launch defaults without launching the real app during unit tests.
- Create: `test/skills/gremllm_electron_ui_testing/test_applescript.sh`
  Purpose: Syntax-check the AppleScript helpers with `osacompile`.
- Create: `test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs`
  Purpose: Verify target selection, expression evaluation, and failure handling in `cdp_eval.js`.
- Create: `test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs`
  Purpose: Verify DOM capture, selection capture, and console interception in `cdp_capture.js`.

## Scope Guardrails

- Keep this as one repo-local skill. Do not split it into multiple skills in v1.
- Do not add new npm dependencies or modify `package.json`.
- Do not add CI/headless flows, packaged-app support, screenshot pipelines, or generic Electron-repo abstractions.
- Do not mutate canonical fixtures in `resources/`.
- Default to disposable workspaces and read-only UI inspection. Only selection/click/scroll interactions needed for validation are in scope.
- Do not add extra repo docs beyond `SKILL.md` and `references/repo-facts.md`.

### Task 1: Prepare Disposable Workspace Fixtures

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh`
- Create: `test/skills/gremllm_electron_ui_testing/test_helpers.sh`
- Test: `test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh`

- [ ] **Step 1: Write the failing shell tests for fixture creation**

Create `test/skills/gremllm_electron_ui_testing/test_helpers.sh`:

```bash
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
```

Create `test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh`:

```bash
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
```

- [ ] **Step 2: Run the shell test to verify it fails**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh
```

Expected:
- FAIL because `.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh` does not exist yet

- [ ] **Step 3: Implement disposable fixture creation**

Create `.agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh`:

```bash
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
```

Run:

```bash
chmod +x .agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh test/skills/gremllm_electron_ui_testing/test_helpers.sh test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh
```

- [ ] **Step 4: Run the shell test to verify it passes**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh
```

Expected:
- PASS with no output
- Both file-backed and folder-backed fixture cases succeed

- [ ] **Step 5: Commit the fixture helper**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/scripts/prepare_fixture.sh test/skills/gremllm_electron_ui_testing/test_helpers.sh test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh
git commit -m "feat(skill): add disposable workspace fixture helper"
```

### Task 2: Launch Or Attach To The Local App

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh`
- Test: `test/skills/gremllm_electron_ui_testing/test_launch_app.sh`

- [ ] **Step 1: Write the failing shell test for attach-first launch behavior**

Create `test/skills/gremllm_electron_ui_testing/test_launch_app.sh`:

```bash
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
    res.end(JSON.stringify([{id: "page-1", type: "page", title: "Gremllm", webSocketDebuggerUrl: `ws://127.0.0.1:${port}/devtools/page/1`}]));
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
    res.end(JSON.stringify([{id: "attached-page", type: "page", title: "Gremllm", webSocketDebuggerUrl: `ws://127.0.0.1:${port}/devtools/page/attached`}]));
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
```

- [ ] **Step 2: Run the shell test to verify it fails**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_launch_app.sh
```

Expected:
- FAIL because `.agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh` does not exist yet

- [ ] **Step 3: Implement attach-first app launch**

Create `.agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh`:

```bash
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
```

Run:

```bash
chmod +x .agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh test/skills/gremllm_electron_ui_testing/test_launch_app.sh
```

- [ ] **Step 4: Run the shell test to verify it passes**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_launch_app.sh
```

Expected:
- PASS with no output
- Attach case reuses the existing renderer target
- Launch case builds, launches Electron with `--remote-debugging-port=<port>`, and returns JSON describing the new session

- [ ] **Step 5: Commit the launch helper**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/scripts/launch_app.sh test/skills/gremllm_electron_ui_testing/test_launch_app.sh
git commit -m "feat(skill): add attach-first electron launch helper"
```

### Task 3: Add Repo-Specific AppleScript Helpers

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/focus_app.applescript`
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/open_workspace.applescript`
- Test: `test/skills/gremllm_electron_ui_testing/test_applescript.sh`

- [ ] **Step 1: Write the failing AppleScript syntax test**

Create `test/skills/gremllm_electron_ui_testing/test_applescript.sh`:

```bash
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
```

- [ ] **Step 2: Run the syntax test to verify it fails**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_applescript.sh
```

Expected:
- FAIL because the AppleScript files do not exist yet

- [ ] **Step 3: Implement the macOS UI helpers**

Create `.agents/skills/gremllm-electron-ui-testing/scripts/focus_app.applescript`:

```applescript
on run argv
	set appName to "Electron"
	if (count of argv) > 0 then set appName to item 1 of argv

	try
		tell application appName to activate
		tell application "System Events"
			if not (exists process appName) then error "Process " & appName & " is not running."
			tell process appName
				set frontmost to true
			end tell
		end tell
	on error errMsg number errNum
		error "focus_app.applescript failed for " & appName & ": " & errMsg number errNum
	end try
end run
```

Create `.agents/skills/gremllm-electron-ui-testing/scripts/open_workspace.applescript`:

```applescript
on waitForOpenDialog(appName, attemptCount)
	tell application "System Events"
		repeat attemptCount times
			try
				if exists sheet 1 of window 1 of process appName then return true
			end try
			try
				if exists window "Open Workspace Folder" of process appName then return true
			end try
			delay 0.25
		end repeat
	end tell
	error "Timed out waiting for Open Workspace Folder dialog."
end waitForOpenDialog

on waitForGoToFolder(appName, attemptCount)
	tell application "System Events"
		repeat attemptCount times
			try
				if exists window "Go to the folder" of process appName then return true
			end try
			try
				if exists text field 1 of sheet 1 of sheet 1 of window 1 of process appName then return true
			end try
			delay 0.25
		end repeat
	end tell
	error "Timed out waiting for Go to the folder sheet."
end waitForGoToFolder

on run argv
	if (count of argv) is less than 1 then error "open_workspace.applescript requires a workspace path argument."

	set workspacePath to POSIX path of (POSIX file (item 1 of argv))
	set appName to "Electron"
	if (count of argv) > 1 then set appName to item 2 of argv

	try
		tell application appName to activate
		tell application "System Events"
			if not (exists process appName) then error "Process " & appName & " is not running."
			tell process appName
				set frontmost to true
				keystroke "o" using command down
			end tell
		end tell

		my waitForOpenDialog(appName, 40)

		tell application "System Events"
			tell process appName
				keystroke "g" using {command down, shift down}
			end tell
		end tell

		my waitForGoToFolder(appName, 20)

		tell application "System Events"
			tell process appName
				keystroke workspacePath
				key code 36
				delay 0.2
				key code 36
			end tell
		end tell
	on error errMsg number errNum
		error "open_workspace.applescript failed for " & workspacePath & ": " & errMsg number errNum
	end try
end run
```

- [ ] **Step 4: Run the syntax test to verify it passes**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_applescript.sh
```

Expected:
- PASS with no output
- Both AppleScript files compile cleanly via `osacompile`

- [ ] **Step 5: Commit the AppleScript helpers**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/scripts/focus_app.applescript .agents/skills/gremllm-electron-ui-testing/scripts/open_workspace.applescript test/skills/gremllm_electron_ui_testing/test_applescript.sh
git commit -m "feat(skill): add macos app focus and workspace open helpers"
```

### Task 4: Add Low-Level CDP Evaluation

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js`
- Test: `test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs`

- [ ] **Step 1: Write the failing Node tests for target selection and evaluation**

Create `test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs`:

```javascript
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const cdpEval = require("../../../.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js");

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    queueMicrotask(() => this.#emit("open", {}));
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send(rawMessage) {
    const message = JSON.parse(rawMessage);

    if (message.method === "Runtime.enable") {
      queueMicrotask(() => this.#emit("message", { data: JSON.stringify({ id: message.id, result: {} }) }));
      return;
    }

    if (message.method === "Runtime.evaluate") {
      queueMicrotask(() =>
        this.#emit("message", {
          data: JSON.stringify({
            id: message.id,
            result: {
              result: {
                type: "string",
                value: "Gremllm",
                description: "Gremllm"
              }
            }
          })
        })
      );
    }
  }

  close() {
    this.#emit("close", {});
  }

  #emit(type, payload) {
    for (const listener of this.listeners.get(type) || []) {
      listener(payload);
    }
  }
}

test("evaluateExpression returns normalized JSON from the Gremllm target", async () => {
  const result = await cdpEval.evaluateExpression({
    expression: "document.title",
    port: 9222,
    title: "Gremllm",
    fetchImpl: async () => ({
      ok: true,
      json: async () => ([
        {
          id: "page-1",
          type: "page",
          title: "Gremllm",
          webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/1"
        }
      ])
    }),
    WebSocketImpl: FakeWebSocket
  });

  assert.deepEqual(result, {
    target: { id: "page-1", title: "Gremllm" },
    result: { type: "string", value: "Gremllm", description: "Gremllm" }
  });
});

test("evaluateExpression throws a clear error when the renderer target is missing", async () => {
  await assert.rejects(
    () =>
      cdpEval.evaluateExpression({
        expression: "document.title",
        port: 9222,
        title: "Gremllm",
        fetchImpl: async () => ({
          ok: true,
          json: async () => ([
            {
              id: "page-2",
              type: "page",
              title: "Other App",
              webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/2"
            }
          ])
        }),
        WebSocketImpl: FakeWebSocket
      }),
    /Renderer target with title "Gremllm" not found/
  );
});
```

- [ ] **Step 2: Run the Node tests to verify they fail**

Run:

```bash
node --test test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs
```

Expected:
- FAIL because `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js` does not exist yet

- [ ] **Step 3: Implement CDP evaluation with normalized output**

Create `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js`:

```javascript
#!/usr/bin/env node

function parseArgs(argv) {
  const parsed = {
    port: Number(process.env.GREMLLM_UI_TEST_PORT || 9222),
    title: process.env.GREMLLM_UI_TEST_TARGET_TITLE || "Gremllm",
    expression: null
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--port") {
      parsed.port = Number(argv[index + 1]);
      index += 1;
      continue;
    }
    if (arg === "--title") {
      parsed.title = argv[index + 1];
      index += 1;
      continue;
    }
    if (parsed.expression === null) {
      parsed.expression = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }

  if (!parsed.expression) {
    throw new Error("Usage: node cdp_eval.js [--port 9222] [--title Gremllm] '<expression>'");
  }

  return parsed;
}

async function fetchTargets(port, fetchImpl = fetch) {
  const response = await fetchImpl(`http://127.0.0.1:${port}/json/list`);
  if (!response.ok) {
    throw new Error(`CDP target list request failed with status ${response.status}`);
  }
  return response.json();
}

function selectTarget(targets, title) {
  const target = targets.find((candidate) => candidate.type === "page" && candidate.title === title);
  if (!target) {
    throw new Error(`Renderer target with title "${title}" not found`);
  }
  return target;
}

function normalizeRemoteObject(remoteObject = {}) {
  if (Object.prototype.hasOwnProperty.call(remoteObject, "value")) {
    return {
      type: remoteObject.type,
      value: remoteObject.value,
      description: remoteObject.description || remoteObject.type
    };
  }

  if (Object.prototype.hasOwnProperty.call(remoteObject, "unserializableValue")) {
    return {
      type: remoteObject.type,
      value: remoteObject.unserializableValue,
      description: remoteObject.description || remoteObject.type
    };
  }

  return {
    type: remoteObject.type || "unknown",
    value: null,
    description: remoteObject.description || remoteObject.type || "unknown"
  };
}

function createCdpClient(WebSocketImpl, wsUrl) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocketImpl(wsUrl);
    const pending = new Map();
    const eventListeners = [];
    let nextId = 0;
    let opened = false;

    const rejectPending = (error) => {
      for (const entry of pending.values()) {
        entry.reject(error);
      }
      pending.clear();
    };

    socket.addEventListener("open", () => {
      opened = true;
      resolve({
        async send(method, params = {}) {
          const id = nextId += 1;
          const payload = { id, method, params };
          return new Promise((innerResolve, innerReject) => {
            pending.set(id, { resolve: innerResolve, reject: innerReject });
            socket.send(JSON.stringify(payload));
          });
        },
        onEvent(listener) {
          eventListeners.push(listener);
        },
        close() {
          socket.close();
        }
      });
    });

    socket.addEventListener("message", (event) => {
      const payload = JSON.parse(event.data);
      if (Object.prototype.hasOwnProperty.call(payload, "id")) {
        const entry = pending.get(payload.id);
        if (!entry) {
          return;
        }
        pending.delete(payload.id);
        if (payload.error) {
          entry.reject(new Error(payload.error.message || "CDP request failed"));
          return;
        }
        entry.resolve(payload.result);
        return;
      }

      for (const listener of eventListeners) {
        listener(payload);
      }
    });

    socket.addEventListener("error", (event) => {
      const error = new Error(event.message || "CDP websocket error");
      if (!opened) {
        reject(error);
        return;
      }
      rejectPending(error);
    });

    socket.addEventListener("close", () => {
      rejectPending(new Error("CDP websocket closed"));
    });
  });
}

async function evaluateExpression({
  expression,
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const targets = await fetchTargets(port, fetchImpl);
  const target = selectTarget(targets, title);
  const client = await createCdpClient(WebSocketImpl, target.webSocketDebuggerUrl);

  try {
    await client.send("Runtime.enable");
    const evaluation = await client.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true
    });

    if (evaluation.exceptionDetails) {
      throw new Error(evaluation.exceptionDetails.text || "Runtime evaluation failed");
    }

    return {
      target: { id: target.id, title: target.title },
      result: normalizeRemoteObject(evaluation.result)
    };
  } finally {
    client.close();
  }
}

async function main(argv = process.argv.slice(2), deps = {}) {
  const { port, title, expression } = parseArgs(argv);
  const result = await evaluateExpression({
    expression,
    port,
    title,
    fetchImpl: deps.fetchImpl || fetch,
    WebSocketImpl: deps.WebSocketImpl || WebSocket
  });
  (deps.stdout || process.stdout).write(`${JSON.stringify(result)}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${JSON.stringify({ ok: false, step: "cdp_eval", message: error.message })}\n`);
    process.exit(1);
  });
}

module.exports = {
  createCdpClient,
  evaluateExpression,
  fetchTargets,
  main,
  normalizeRemoteObject,
  parseArgs,
  selectTarget
};
```

Run:

```bash
chmod +x .agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js
```

- [ ] **Step 4: Run the Node tests to verify they pass**

Run:

```bash
node --test test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs
```

Expected:
- PASS for both tests
- Output shows `2 tests` and `0 failures`

- [ ] **Step 5: Commit the CDP evaluator**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs
git commit -m "feat(skill): add CDP evaluation helper"
```

### Task 5: Add Higher-Level CDP Capture Helpers

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js`
- Test: `test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs`

- [ ] **Step 1: Write the failing Node tests for DOM, selection, and console capture**

Create `test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs`:

```javascript
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const capture = require("../../../.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js");

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    queueMicrotask(() => this.#emit("open", {}));
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send(rawMessage) {
    const message = JSON.parse(rawMessage);

    if (message.method === "Runtime.enable" || message.method === "Log.enable") {
      queueMicrotask(() => this.#emit("message", { data: JSON.stringify({ id: message.id, result: {} }) }));
      if (message.method === "Log.enable") {
        queueMicrotask(() =>
          this.#emit("message", {
            data: JSON.stringify({
              method: "Runtime.consoleAPICalled",
              params: {
                type: "log",
                args: [{ type: "string", value: "captured", description: "captured" }]
              }
            })
          })
        );
      }
      return;
    }

    if (message.method === "Runtime.evaluate") {
      const value = message.params.expression.includes("window.getSelection")
        ? {
            found: true,
            text: "selected text",
            anchorNode: "#text",
            focusNode: "#text",
            commonAncestor: "P"
          }
        : {
            found: true,
            selector: ".document-panel article",
            text: "Fixture text",
            html: "<article>Fixture text</article>"
          };

      queueMicrotask(() =>
        this.#emit("message", {
          data: JSON.stringify({
            id: message.id,
            result: {
              result: {
                type: "object",
                value,
                description: "Object"
              }
            }
          })
        })
      );
    }
  }

  close() {
    this.#emit("close", {});
  }

  #emit(type, payload) {
    for (const listener of this.listeners.get(type) || []) {
      listener(payload);
    }
  }
}

const fetchImpl = async () => ({
  ok: true,
  json: async () => ([
    {
      id: "page-1",
      type: "page",
      title: "Gremllm",
      webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/1"
    }
  ])
});

test("captureDom returns normalized article output", async () => {
  const result = await capture.captureDom({
    selector: ".document-panel article",
    port: 9222,
    title: "Gremllm",
    fetchImpl,
    WebSocketImpl: FakeWebSocket
  });

  assert.equal(result.capture.selector, ".document-panel article");
  assert.equal(result.capture.text, "Fixture text");
});

test("captureSelection returns normalized selection details", async () => {
  const result = await capture.captureSelection({
    port: 9222,
    title: "Gremllm",
    fetchImpl,
    WebSocketImpl: FakeWebSocket
  });

  assert.equal(result.capture.text, "selected text");
  assert.equal(result.capture.commonAncestor, "P");
});

test("captureConsole collects Runtime.consoleAPICalled events", async () => {
  const result = await capture.captureConsole({
    port: 9222,
    title: "Gremllm",
    durationMs: 0,
    fetchImpl,
    WebSocketImpl: FakeWebSocket,
    setTimeoutImpl: (callback) => callback()
  });

  assert.equal(result.entries.length, 1);
  assert.equal(result.entries[0].source, "Runtime.consoleAPICalled");
  assert.equal(result.entries[0].args[0].value, "captured");
});
```

- [ ] **Step 2: Run the Node tests to verify they fail**

Run:

```bash
node --test test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs
```

Expected:
- FAIL because `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js` does not exist yet

- [ ] **Step 3: Implement reusable DOM, selection, and console capture**

Create `.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js`:

```javascript
#!/usr/bin/env node

const {
  createCdpClient,
  evaluateExpression,
  fetchTargets,
  normalizeRemoteObject,
  selectTarget
} = require("./cdp_eval.js");

function parseArgs(argv) {
  const parsed = {
    command: null,
    port: Number(process.env.GREMLLM_UI_TEST_PORT || 9222),
    title: process.env.GREMLLM_UI_TEST_TARGET_TITLE || "Gremllm",
    selector: ".document-panel article",
    durationMs: 1500
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--port") {
      parsed.port = Number(argv[index + 1]);
      index += 1;
      continue;
    }
    if (arg === "--title") {
      parsed.title = argv[index + 1];
      index += 1;
      continue;
    }
    if (arg === "--selector") {
      parsed.selector = argv[index + 1];
      index += 1;
      continue;
    }
    if (arg === "--duration-ms") {
      parsed.durationMs = Number(argv[index + 1]);
      index += 1;
      continue;
    }
    if (parsed.command === null) {
      parsed.command = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }

  if (!parsed.command || !["dom", "selection", "console"].includes(parsed.command)) {
    throw new Error("Usage: node cdp_capture.js <dom|selection|console> [--selector .document-panel article] [--duration-ms 1500] [--port 9222] [--title Gremllm]");
  }

  return parsed;
}

function domExpression(selector) {
  return `(() => {
    const selectorValue = ${JSON.stringify(selector)};
    const node = document.querySelector(selectorValue);
    if (!node) {
      return { found: false, selector: selectorValue };
    }
    return {
      found: true,
      selector: selectorValue,
      text: node.innerText,
      html: node.outerHTML
    };
  })()`;
}

function selectionExpression() {
  return `(() => {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.toString() === "") {
      return { found: false, text: "" };
    }

    const range = selection.getRangeAt(0);
    const ancestor = range.commonAncestorContainer.nodeType === Node.TEXT_NODE
      ? range.commonAncestorContainer.parentElement
      : range.commonAncestorContainer;

    return {
      found: true,
      text: selection.toString(),
      anchorNode: selection.anchorNode ? selection.anchorNode.nodeName : null,
      focusNode: selection.focusNode ? selection.focusNode.nodeName : null,
      commonAncestor: ancestor ? ancestor.nodeName : null
    };
  })()`;
}

async function captureDom({
  selector = ".document-panel article",
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const result = await evaluateExpression({
    expression: domExpression(selector),
    port,
    title,
    fetchImpl,
    WebSocketImpl
  });

  return {
    command: "dom",
    target: result.target,
    capture: result.result.value
  };
}

async function captureSelection({
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const result = await evaluateExpression({
    expression: selectionExpression(),
    port,
    title,
    fetchImpl,
    WebSocketImpl
  });

  return {
    command: "selection",
    target: result.target,
    capture: result.result.value
  };
}

async function captureConsole({
  port = 9222,
  title = "Gremllm",
  durationMs = 1500,
  fetchImpl = fetch,
  WebSocketImpl = WebSocket,
  setTimeoutImpl = setTimeout
}) {
  const targets = await fetchTargets(port, fetchImpl);
  const target = selectTarget(targets, title);
  const client = await createCdpClient(WebSocketImpl, target.webSocketDebuggerUrl);
  const entries = [];

  client.onEvent((payload) => {
    if (payload.method === "Runtime.consoleAPICalled") {
      entries.push({
        source: "Runtime.consoleAPICalled",
        type: payload.params.type,
        args: (payload.params.args || []).map(normalizeRemoteObject)
      });
      return;
    }

    if (payload.method === "Log.entryAdded") {
      entries.push({
        source: "Log.entryAdded",
        level: payload.params.entry.level,
        text: payload.params.entry.text
      });
    }
  });

  try {
    await client.send("Runtime.enable");
    await client.send("Log.enable");
    await new Promise((resolve) => setTimeoutImpl(resolve, durationMs));

    return {
      command: "console",
      target: { id: target.id, title: target.title },
      durationMs,
      entries
    };
  } finally {
    client.close();
  }
}

async function main(argv = process.argv.slice(2), deps = {}) {
  const { command, selector, port, title, durationMs } = parseArgs(argv);
  let result;

  if (command === "dom") {
    result = await captureDom({
      selector,
      port,
      title,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket
    });
  } else if (command === "selection") {
    result = await captureSelection({
      port,
      title,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket
    });
  } else {
    result = await captureConsole({
      port,
      title,
      durationMs,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket,
      setTimeoutImpl: deps.setTimeoutImpl || setTimeout
    });
  }

  (deps.stdout || process.stdout).write(`${JSON.stringify(result)}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${JSON.stringify({ ok: false, step: "cdp_capture", message: error.message })}\n`);
    process.exit(1);
  });
}

module.exports = {
  captureConsole,
  captureDom,
  captureSelection,
  main,
  parseArgs
};
```

Run:

```bash
chmod +x .agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js
```

- [ ] **Step 4: Run the Node tests to verify they pass**

Run:

```bash
node --test test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs
```

Expected:
- PASS for all three tests
- Output shows `3 tests` and `0 failures`

- [ ] **Step 5: Commit the capture helper**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs
git commit -m "feat(skill): add CDP evidence capture helpers"
```

### Task 6: Write The Skill And Repo Facts

**Files:**
- Create: `.agents/skills/gremllm-electron-ui-testing/SKILL.md`
- Create: `.agents/skills/gremllm-electron-ui-testing/references/repo-facts.md`

- [ ] **Step 1: Write the compact repo facts reference**

Create `.agents/skills/gremllm-electron-ui-testing/references/repo-facts.md`:

````markdown
# Gremllm Electron UI Testing Repo Facts

- Local app process name for UI automation: `Electron`
- Repo root is the working directory for build and direct-launch commands
- Preferred local validation launch path: `npm run build` then `./node_modules/.bin/electron . --remote-debugging-port=9222`
- Default CDP port: `9222`
- Primary renderer target title: `Gremllm`
- Disposable fixture outputs must live under `/tmp/gremllm-*`
- Document DOM anchor for validation: `.document-panel article`
- Workspace picker dialog title: `Open Workspace Folder`
- File-menu accelerator for opening a workspace: `Cmd+O`
- Canonical sample markdown fixture already in repo: `resources/gremllm-launch-log.md`
- Build and test commands from `package.json`: `npm run build`, `npm run test:ci`
````

- [ ] **Step 2: Write the repo-local skill instructions and worked example**

Create `.agents/skills/gremllm-electron-ui-testing/SKILL.md`:

````markdown
---
name: gremllm-electron-ui-testing
description: Use when validating behavior in the live Gremllm Electron app through disposable fixtures, repo-specific macOS app control, CDP DOM inspection, or structured evidence capture.
---

# Gremllm Electron UI Testing

Use this skill when unit tests or static code inspection are not enough and the task requires driving the real running `gremllm` Electron app.

## Read First

- Repo facts live in `references/repo-facts.md`.
- Default safety posture:
  - use disposable workspaces under `/tmp/gremllm-*`
  - prefer attach over restart
  - keep interactions read-only unless the user explicitly asks for mutation
  - stop and report permission failures instead of improvising around them

## Available Helpers

- `scripts/prepare_fixture.sh --source resources/gremllm-launch-log.md`
- `scripts/launch_app.sh`
- `osascript scripts/focus_app.applescript`
- `osascript scripts/open_workspace.applescript /tmp/gremllm-abc123`
- `node scripts/cdp_eval.js 'document.title'`
- `node scripts/cdp_capture.js dom --selector '.document-panel article'`
- `node scripts/cdp_capture.js selection`
- `node scripts/cdp_capture.js console --duration-ms 1500`

## Workflow

### 1. Prepare Fixture

- Never mutate canonical fixtures in `resources/`.
- Prefer `resources/gremllm-launch-log.md` for a known-good document fixture.
- Use `scripts/prepare_fixture.sh` to create a disposable workspace under `/tmp/gremllm-*`.

### 2. Launch Or Attach

- Run `scripts/launch_app.sh`.
- The helper first tries `http://127.0.0.1:9222/json/list` and reuses an existing `Gremllm` target when available.
- If no valid target exists, it runs `npm run build` from repo root and launches `electron . --remote-debugging-port=9222`.

### 3. Focus And Open Workspace

- Bring the app forward with `osascript scripts/focus_app.applescript`.
- Open the workspace picker with `osascript scripts/open_workspace.applescript <workspace-path>`.
- If macOS accessibility or automation permissions block the flow, stop and report the exact failed script.

### 4. Inspect And Drive

- Use `node scripts/cdp_eval.js '<expression>'` for ad hoc renderer inspection or read-only DOM interactions.
- Use `node scripts/cdp_capture.js dom --selector '.document-panel article'` to capture normalized DOM evidence.
- Use `node scripts/cdp_capture.js selection` to inspect the current live selection.
- Use `node scripts/cdp_capture.js console --duration-ms 1500` to collect console events during an interaction window.

### 5. Capture Evidence

- Prefer JSON output from the helper scripts over prose notes.
- Reuse the JSON in findings docs, regression notes, or terminal summaries.
- Do not claim the app was validated unless the workspace was opened and the renderer target was actually driven.

## Failure Handling

- `prepare_fixture.sh` fails: report the missing source path or invalid `/tmp/gremllm-*` output path.
- `launch_app.sh` fails: report whether build failed, Electron failed to launch, or the CDP endpoint never produced a `Gremllm` page target.
- `focus_app.applescript` or `open_workspace.applescript` fails: report the exact AppleScript error and call out likely macOS accessibility/automation permissions.
- `cdp_eval.js` fails: report whether the CDP endpoint was missing, the renderer target was not found, or the runtime evaluation threw.
- `cdp_capture.js` fails: report the exact subcommand and the underlying CDP failure.

## Worked Example

This example follows the document excerpt locator spike pattern, but uses the building blocks directly instead of one orchestration command.

```bash
ROOT="$(pwd)"
SKILL_DIR="$ROOT/.agents/skills/gremllm-electron-ui-testing"

workspace="$("$SKILL_DIR/scripts/prepare_fixture.sh" --source resources/gremllm-launch-log.md)"
"$SKILL_DIR/scripts/launch_app.sh"
osascript "$SKILL_DIR/scripts/focus_app.applescript"
osascript "$SKILL_DIR/scripts/open_workspace.applescript" "$workspace"

node "$SKILL_DIR/scripts/cdp_capture.js" dom --selector '.document-panel article'

node "$SKILL_DIR/scripts/cdp_capture.js" console --duration-ms 1500 >/tmp/gremllm-ui-console.json &
console_pid="$!"

node "$SKILL_DIR/scripts/cdp_eval.js" '(() => {
  const article = document.querySelector(".document-panel article");
  const paragraph = article.querySelector("p");
  const textNode = paragraph.firstChild;
  const range = document.createRange();
  range.setStart(textNode, 0);
  range.setEnd(textNode, Math.min(24, textNode.textContent.length));
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
  console.log(JSON.stringify({kind: "ui-test-selection", text: selection.toString()}));
  return {
    selectedText: selection.toString(),
    articleText: article.innerText
  };
})()'

wait "$console_pid"
cat /tmp/gremllm-ui-console.json
```

Success looks like:

- `launch_app.sh` prints JSON with `"status":"attached"` or `"status":"launched"`
- DOM capture reports `found: true` for `.document-panel article`
- the console capture JSON includes one `Runtime.consoleAPICalled` entry with `kind: "ui-test-selection"`
````

- [ ] **Step 3: Verify the docs include every required repo fact and the worked example**

Run:

```bash
rg -n "Electron|9222|Gremllm|document-panel article|Open Workspace Folder|Worked Example|prepare_fixture.sh|launch_app.sh|cdp_eval.js|cdp_capture.js" .agents/skills/gremllm-electron-ui-testing/SKILL.md .agents/skills/gremllm-electron-ui-testing/references/repo-facts.md
```

Expected:
- Matches for all of the hardcoded repo facts from the spec
- Matches for the worked example and each helper script

- [ ] **Step 4: Commit the skill docs**

Run:

```bash
git add .agents/skills/gremllm-electron-ui-testing/SKILL.md .agents/skills/gremllm-electron-ui-testing/references/repo-facts.md
git commit -m "docs(skill): add gremllm electron ui testing skill"
```

### Task 7: Run Real Validation Against The Live App

**Files:**
- Verify only: `.agents/skills/gremllm-electron-ui-testing/`
- Verify only: `resources/gremllm-launch-log.md`

- [x] **Step 1: Run the focused automated checks first**

Run:

```bash
bash test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh
bash test/skills/gremllm_electron_ui_testing/test_launch_app.sh
bash test/skills/gremllm_electron_ui_testing/test_applescript.sh
node --test test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs
```

Expected:
- PASS for all shell and Node tests
- No failures before the real-app session starts

Observed on 2026-04-15:
- `bash test/skills/gremllm_electron_ui_testing/test_prepare_fixture.sh` passed
- `bash test/skills/gremllm_electron_ui_testing/test_launch_app.sh` passed
- `bash test/skills/gremllm_electron_ui_testing/test_applescript.sh` passed
- `node --test test/skills/gremllm_electron_ui_testing/cdp_eval_test.mjs test/skills/gremllm_electron_ui_testing/cdp_capture_test.mjs` passed with 5/5 tests green

- [ ] **Step 2: Run one real local validation session**

Run:

```bash
ROOT="$(pwd)"
SKILL_DIR="$ROOT/.agents/skills/gremllm-electron-ui-testing"

workspace="$("$SKILL_DIR/scripts/prepare_fixture.sh" --source resources/gremllm-launch-log.md)"
"$SKILL_DIR/scripts/launch_app.sh"
osascript "$SKILL_DIR/scripts/focus_app.applescript"
osascript "$SKILL_DIR/scripts/open_workspace.applescript" "$workspace"
node "$SKILL_DIR/scripts/cdp_capture.js" dom --selector '.document-panel article'
node "$SKILL_DIR/scripts/cdp_capture.js" console --duration-ms 1500 >/tmp/gremllm-ui-console.json &
console_pid="$!"
node "$SKILL_DIR/scripts/cdp_eval.js" '(() => {
  const article = document.querySelector(".document-panel article");
  const paragraph = article.querySelector("p");
  const textNode = paragraph.firstChild;
  const range = document.createRange();
  range.setStart(textNode, 0);
  range.setEnd(textNode, Math.min(24, textNode.textContent.length));
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
  console.log(JSON.stringify({kind: "ui-test-selection", text: selection.toString()}));
  return selection.toString();
})()'
wait "$console_pid"
cat /tmp/gremllm-ui-console.json
```

Expected:
- `launch_app.sh` returns one-line JSON with `"status":"attached"` or `"status":"launched"`
- the workspace opens successfully in the live app
- `cdp_capture.js dom` returns JSON with `"found":true` for `.document-panel article`
- `cdp_eval.js` returns a non-empty selected string
- `/tmp/gremllm-ui-console.json` includes a `Runtime.consoleAPICalled` entry showing the selection evidence

Observed on 2026-04-15 attempt:
- `prepare_fixture.sh` created a disposable workspace at `/tmp/gremllm-fixture-GjXn49` from `resources/gremllm-launch-log.md`
- `launch_app.sh` succeeded and returned `{"status":"launched","port":9222,"title":"Gremllm","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/F46A94C6DF57820BDC15E1BC01801570","pid":68179}`
- `focus_app.applescript` returned without error
- The run paused at `open_workspace.applescript` with `open_workspace.applescript failed for /tmp/gremllm-fixture-GjXn49: Timed out waiting for Go to the folder sheet. (-2700)`
- Because the workspace did not open, `node .agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js dom --selector '.document-panel article'` returned `{"command":"dom","target":{"id":"F46A94C6DF57820BDC15E1BC01801570","title":"Gremllm"},"capture":{"found":false,"selector":".document-panel article"}}`
- Because no document article was present, `cdp_eval.js` returned `{"ok":false,"step":"cdp_eval","message":"Uncaught"}`
- `/tmp/gremllm-ui-console.json` captured only the standard Electron CSP warning and did not contain the expected `ui-test-selection` console entry
- Resume from here next time: debug why `open_workspace.applescript` does not reach the "Go to the folder" sheet after `Cmd+O` and `Cmd+Shift+G` in the live app on this machine

- [x] **Step 3: Confirm validation did not mutate the repo**

Run:

```bash
git status --short
```

Expected:
- No unexpected file modifications from the validation pass
- Temporary evidence remains in `/tmp`, not in tracked repo files

Observed on 2026-04-15:
- `git status --short` was clean immediately after the validation attempt
- This plan-file note is the only intentional tracked change after that clean check

## Self-Review

### Spec Coverage

- Disposable fixture workflow: Task 1
- Launch-or-attach behavior with `9222` and `Gremllm` target title: Task 2
- Repo-specific app focus and workspace open flow: Task 3
- CDP evaluation for inspection and safe read-only interaction: Task 4
- Structured DOM/selection/console evidence capture: Task 5
- Repo-local skill instructions, safety posture, failure branches, and worked example: Task 6
- Required real local validation session: Task 7

### Placeholder Scan

- No placeholder markers or vague deferred-work notes remain in the task steps.
- Every created file has an exact path.
- Every code-writing step includes concrete file content.
- Every verification step includes exact commands and expected outcomes.

### Type Consistency

- Script directory and skill path stay consistent: `.agents/skills/gremllm-electron-ui-testing/`
- Shared defaults stay consistent across tasks: app name `Electron`, port `9222`, target title `Gremllm`, DOM anchor `.document-panel article`
- Test paths stay consistent under `test/skills/gremllm_electron_ui_testing/`
