# Docs Retention Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `docs/` to the approved archival set, add one durable ACP closeout note, migrate load-bearing spike findings into code comments, and remove obsolete docs without leaving dangling references.

**Architecture:** Execute the cleanup in three passes: first add the durable replacement artifact (`docs/2026-04-21-acp-packaged-host-closeout.md`), then update production comments and surviving docs so they no longer depend on transient spike notes, then delete the obsolete docs and verify the repo no longer references them. This is a documentation-and-comments change only; no runtime behavior should change, so verification is based on repository scans and diff hygiene rather than app behavior tests.

**Tech Stack:** Markdown, ClojureScript, CommonJS, Electron Forge config, `rg`, `git`

---

## File Structure

**Create**

- `docs/2026-04-21-acp-packaged-host-closeout.md` — short durable branch closeout for the packaged ACP host decision, with primary-source references and production touchpoints
- `docs/plans/2026-04-21-docs-retention-cleanup.md` — this implementation plan

**Modify**

- `src/gremllm/main/effects/acp.cljs` — strengthen the `session-meta` comment so it explains the pinned adapter behavior and the `ELECTRON_RUN_AS_NODE` / `settingSources: []` rationale with source anchors
- `src/js/acp/index.js` — explain why the bridge is intentionally in-process and why paired `TransformStream`s are the chosen transport
- `forge.config.js` — explain why `FuseV1Options.RunAsNode` must stay enabled and what trade-off it reintroduces
- `src/js/acp/permission.js` — keep the local product nuance that approved edits are proposal-path approvals, not immediate disk writes
- `docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md` — replace the deleted spike-plan reference with the durable closeout note and adjust status text so the retained spec reads as a historical option-space record
- `docs/plans/2026-02-11-scooter-vertical-slices.md` — remove the deleted-file reference from the `Supersedes` line while preserving the historical relationship

**Delete**

- `docs/plans/2026-02-09-document-first-pivot.md`
- `docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md`
- `docs/plans/2026-04-21-spike-in-process-acp-host.md`
- `docs/2026-04-20-acp-packaged-app-launch-r1-r4-research-memo.md`
- `docs/acp-poc-learnings.md`
- `docs/specs/2026-04-18-instrument-acp-stack.md`
- `docs/2026-04-20-acp-current-test-diagnostic-findings.md`

**Verification only**

- `docs/specs/2026-04-21-docs-retention-cleanup-design.md` — keep unchanged; exclude it from the deleted-doc reference scan because it intentionally records the removal set

### Task 1: Create the Durable ACP Closeout Note

**Files:**
- Create: `docs/2026-04-21-acp-packaged-host-closeout.md`
- Test: none; verify by repository scans in this task

- [ ] **Step 1: Write the closeout note with the final decision and source anchors**

```md
# ACP Packaged Host Closeout

**Date:** 2026-04-21
**Status:** Final branch closeout for packaged-agent launch work

## Outcome

- Gremllm hosts `claude-agent-acp` in-process through `src/js/acp/index.js`.
- Packaged launch depends on two coordinated controls:
  - `FuseV1Options.RunAsNode: true` in `forge.config.js`
  - `ELECTRON_RUN_AS_NODE=1` injected via `src/gremllm/main/effects/acp.cljs` session metadata
- `settingSources: []` is passed on session creation to suppress Claude Code SDK user/project/local settings loading and reduce watcher pressure during Gremllm sessions.
- The packaged path does not require `asarUnpack` for the pinned Claude Agent SDK CLI path.

## Production Touchpoints

- `forge.config.js`
- `src/gremllm/main/effects/acp.cljs`
- `src/js/acp/index.js`
- `src/js/acp/permission.js`

## Primary Sources

- Electron fuses docs: `https://packages.electronjs.org/fuses`
- Pinned adapter behavior in this repo: `node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137`
- Upstream adapter repo: `https://github.com/agentclientprotocol/claude-agent-acp/tree/v0.29.2`
- ACP SDK stream/connection implementation in this repo:
  - `node_modules/@agentclientprotocol/sdk/dist/acp.js`
  - `node_modules/@agentclientprotocol/sdk/dist/stream.js`
- Upstream ACP SDK repo: `https://github.com/agentclientprotocol/typescript-sdk`

## Why These Knobs Exist

`claude-agent-acp@0.29.2` reads `params._meta?.claudeCode?.options`, merges `env`, defaults `settingSources`, and then hardcodes `executable: process.execPath` for the non-static-binary path. In packaged Electron, `process.execPath` is the app binary, so `RunAsNode` must remain enabled and the child env must include `ELECTRON_RUN_AS_NODE=1` or the binary relaunches the app instead of acting as a Node interpreter. `settingSources: []` remains because the same adapter path defaults to `["user", "project", "local"]`, which adds settings-file loading that Gremllm does not need.

## Trade-Offs To Re-Evaluate Before Future Changes

- `RunAsNode` makes `ELECTRON_RUN_AS_NODE` meaningful again for the packaged app binary.
- The in-process bridge depends on ACP SDK generic-stream support and Gremllm's paired `TransformStream` bridge rather than subprocess stdio.
- The adapter behavior described above is pinned to `@agentclientprotocol/claude-agent-acp@0.29.2`; re-check the sources on dependency upgrades.
```

- [ ] **Step 2: Verify the new note contains the required anchors**

Run:

```bash
rg -n "Outcome|RunAsNode|ELECTRON_RUN_AS_NODE|settingSources|TransformStream|Primary Sources" \
  docs/2026-04-21-acp-packaged-host-closeout.md
```

Expected: six or more hits covering the outcome, runtime knobs, and source sections.

- [ ] **Step 3: Commit the closeout note**

```bash
git add docs/2026-04-21-acp-packaged-host-closeout.md
git commit -m "docs: add ACP packaged host closeout"
```

### Task 2: Annotate the Production Code With Primary-Source-Backed Comments

**Files:**
- Modify: `src/gremllm/main/effects/acp.cljs:146-161`
- Modify: `src/js/acp/index.js:41-49`
- Modify: `forge.config.js:36-44`
- Modify: `src/js/acp/permission.js:63-72`
- Test: none; verify by targeted text scans in this task

- [ ] **Step 1: Replace the `session-meta` comment in `src/gremllm/main/effects/acp.cljs`**

```clojure
;; TODO: session-meta embeds adapter-internal knobs whose shape is keyed to the
;; pinned claude-agent-acp session setup path (local package
;; node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137).
;; This effect file currently owns three concerns: connection lifecycle, ACP public API,
;; and Claude-adapter overrides. The overrides want their own home before non-spike use.
(def ^:private session-meta
  "Adapter reads params._meta.claudeCode.options, merges env, and defaults
   settingSources in the pinned session-setup path above. It then hardcodes
   executable: process.execPath for the non-static-binary branch, so
   _meta.claudeCode.options.executable is not a working override in this repo's
   pinned adapter version.

   Gremllm therefore injects:
   - ELECTRON_RUN_AS_NODE=1 so the packaged Electron binary (process.execPath)
     acts as a Node interpreter instead of relaunching the app window. This
     depends on FuseV1Options.RunAsNode remaining enabled; see
     https://packages.electronjs.org/fuses
   - settingSources: [] to suppress Claude Code SDK user/project/local settings
     loading for Gremllm sessions. The adapter's own SettingsManager lifecycle
     remains separate."
  #js {:claudeCode
       #js {:options
            #js {:env            #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources #js []}}})
```

- [ ] **Step 2: Replace the `TransformStream` comment in `src/js/acp/index.js`**

```js
  // The ACP SDK accepts generic streams for ndjson transport (see the pinned
  // local packages node_modules/@agentclientprotocol/sdk/dist/acp.js and
  // node_modules/@agentclientprotocol/sdk/dist/stream.js), so Gremllm hosts
  // claude-agent-acp in-process and bridges client/agent traffic with paired
  // TransformStreams instead of subprocess stdio.
  // TODO: failure propagation is unexamined — if the agent side throws mid-message or the
  // ndjson codec encounters a malformed frame, it's unclear whether the error surfaces to
  // the connection's promise chain or is silently dropped.
  const clientToAgent = new TransformStream();
  const agentToClient = new TransformStream();
```

- [ ] **Step 3: Replace the `RunAsNode` comment in `forge.config.js`**

```js
		// Fuses are used to enable/disable various Electron functionality
		// at package time, before code signing the application
		new FusesPlugin({
			version: FuseVersion.V1,
			// Electron's fuse docs make RunAsNode the switch that disables or enables
			// ELECTRON_RUN_AS_NODE. Gremllm keeps it on because the pinned
			// claude-agent-acp session setup hardcodes executable: process.execPath
			// (local package node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1131-1133),
			// and packaged launch only works when session-meta also injects
			// ELECTRON_RUN_AS_NODE=1. Trade-off: enabling this fuse makes that env var
			// meaningful again for the app binary. Source: https://packages.electronjs.org/fuses
			[FuseV1Options.RunAsNode]: true,
```

- [ ] **Step 4: Tighten the workflow comment in `src/js/acp/permission.js`**

```js
        // Critical workflow nuance: for Gremllm, approving an in-workspace
        // edit/write means "allow the agent to complete the proposal path", not
        // "write the file immediately". The ACP bridge keeps writeTextFile as a
        // dry-run no-op, so the successful path here is still non-mutating and
        // disk changes remain gated behind Gremllm's later accept/reject flow.
        // Rejecting permission changes the semantics entirely: Claude reports
        // that the user refused the tool, and the proposal step fails instead
        // of returning a reviewable diff.
```

- [ ] **Step 5: Verify the new comments are present**

Run:

```bash
rg -n "packages.electronjs.org/fuses|process.execPath|settingSources|TransformStream|proposal path" \
  src/gremllm/main/effects/acp.cljs \
  src/js/acp/index.js \
  forge.config.js \
  src/js/acp/permission.js
```

Expected: hits in all four files.

- [ ] **Step 6: Commit the comment updates**

```bash
git add src/gremllm/main/effects/acp.cljs src/js/acp/index.js forge.config.js src/js/acp/permission.js
git commit -m "docs: annotate ACP packaged host rationale"
```

### Task 3: Repair Surviving Docs So They No Longer Depend on Deleted Notes

**Files:**
- Modify: `docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md:3-6`
- Modify: `docs/plans/2026-02-11-scooter-vertical-slices.md:3-4`
- Test: none; verify by targeted text scans in this task

- [ ] **Step 1: Replace the stale plan reference in the kept ACP investigation spec**

```md
**Date:** 2026-04-20
**Status:** Historical option-space record; final outcome captured in the closeout note
**Related:** [docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md), [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js), [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js)
**Closeout:** [docs/2026-04-21-acp-packaged-host-closeout.md](/Users/paul/Projects/gremllm/.worktrees/spike-in-process-acp-host/docs/2026-04-21-acp-packaged-host-closeout.md)
```

- [ ] **Step 2: Remove the deleted-file reference from the surviving scooter plan**

```md
**Date:** 2026-02-11
**Supersedes:** earlier abstraction-first document-first pivot planning from 2026-02-09
**References:** `test/acp-native-tools-spike.mjs` (read behavior), `test/acp-agent-document-interaction.mjs` (write/diff behavior — primary evidence for S4b schema gaps)
```

- [ ] **Step 3: Verify the surviving docs no longer reference the doomed paths**

Run:

```bash
rg -n "2026-04-20-acp-packaged-app-launch-research-spikes|2026-02-09-document-first-pivot" \
  docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md \
  docs/plans/2026-02-11-scooter-vertical-slices.md
```

Expected: no output.

- [ ] **Step 4: Commit the reference repairs**

```bash
git add docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md docs/plans/2026-02-11-scooter-vertical-slices.md
git commit -m "docs: retarget kept doc references"
```

### Task 4: Remove the Approved Obsolete Docs

**Files:**
- Delete: `docs/plans/2026-02-09-document-first-pivot.md`
- Delete: `docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md`
- Delete: `docs/plans/2026-04-21-spike-in-process-acp-host.md`
- Delete: `docs/2026-04-20-acp-packaged-app-launch-r1-r4-research-memo.md`
- Delete: `docs/acp-poc-learnings.md`
- Delete: `docs/specs/2026-04-18-instrument-acp-stack.md`
- Delete: `docs/2026-04-20-acp-current-test-diagnostic-findings.md`
- Test: none; verify by file listing in this task

- [ ] **Step 1: Delete the obsolete docs with `git rm`**

```bash
git rm \
  docs/plans/2026-02-09-document-first-pivot.md \
  docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md \
  docs/plans/2026-04-21-spike-in-process-acp-host.md \
  docs/2026-04-20-acp-packaged-app-launch-r1-r4-research-memo.md \
  docs/acp-poc-learnings.md \
  docs/specs/2026-04-18-instrument-acp-stack.md \
  docs/2026-04-20-acp-current-test-diagnostic-findings.md
```

Expected: seven `rm '…'` lines.

- [ ] **Step 2: Verify the docs tree now matches the approved keep/remove set**

Run:

```bash
find docs -maxdepth 2 -type f | sort
```

Expected output contains these relevant files and omits the seven deleted ones:

```text
docs/2026-03-05-diff-match-patch-anchoring-proposal.md
docs/2026-04-21-acp-packaged-host-closeout.md
docs/acp-client-agent-interaction-research-2026-02-09.md
docs/gemini-generated-flyer.jpg
docs/plans/2026-02-11-scooter-vertical-slices.md
docs/plans/2026-04-21-docs-retention-cleanup.md
docs/specs/2026-04-14-s8-staged-selections-ai-context-design.md
docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md
docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md
docs/specs/2026-04-21-docs-retention-cleanup-design.md
```

- [ ] **Step 3: Commit the deletions**

```bash
git add -u docs
git commit -m "docs: remove obsolete planning artifacts"
```

### Task 5: Run Final Verification and Prepare Execution Handoff

**Files:**
- Modify: none
- Test: repository scans and diff hygiene only

- [ ] **Step 1: Run the deleted-doc reference scan, excluding the cleanup spec and this plan**

Run:

```bash
rg -n "2026-02-09-document-first-pivot|2026-04-20-acp-packaged-app-launch-research-spikes|2026-04-21-spike-in-process-acp-host|2026-04-20-acp-packaged-app-launch-r1-r4-research-memo|acp-poc-learnings|2026-04-18-instrument-acp-stack|2026-04-20-acp-current-test-diagnostic-findings" \
  docs src test README.md forge.config.js \
  --glob '!docs/specs/2026-04-21-docs-retention-cleanup-design.md' \
  --glob '!docs/plans/2026-04-21-docs-retention-cleanup.md'
```

Expected: no output.

- [ ] **Step 2: Verify the source-backed ACP rationale is present in the new note and code comments**

Run:

```bash
rg -n "packages.electronjs.org/fuses|process.execPath|settingSources|TransformStream|proposal path" \
  docs/2026-04-21-acp-packaged-host-closeout.md \
  src/gremllm/main/effects/acp.cljs \
  src/js/acp/index.js \
  forge.config.js \
  src/js/acp/permission.js
```

Expected: multiple hits across all five files.

- [ ] **Step 3: Run diff hygiene checks**

Run:

```bash
git diff --check
git status --short
```

Expected:
- `git diff --check` prints nothing
- `git status --short` shows a clean worktree

- [ ] **Step 4: Record the intentional non-test decision in the handoff**

```md
Verification note for final summary:
- No `npm` test run was needed because the change set is documentation and comments only; runtime behavior was not modified.
```

- [ ] **Step 5: Commit the final verification note only if a tracked file changed during verification**

```bash
git status --short
```

Expected: no output. If output is still empty, do not create another commit.
