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
