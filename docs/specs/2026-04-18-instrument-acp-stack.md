# Spec: Instrument the ACP stack for visibility into issue #211

Branch: `diag/instrument-acp-stack`
Date: 2026-04-18
Issue: https://github.com/Quantisan/gremllm/issues/211

## Context

Issue #211 reports that Gremllm's document-first dry-run pattern — which relied on the ACP server calling the client's `writeTextFile` capability when the agent edits a file — stopped working somewhere between `claude-agent-acp` v0.16.x and the current v0.29.2. The agent's Edit/Write tools now execute via `claude-agent-sdk` internals and disk mutates directly; the client never sees `writeTextFile`. Two assertions in `test/gremllm/main/effects/acp_integration_test.cljs` (lines 111 and 160) fail accordingly.

The user's priority on this branch is **observability, not a fix**. We want to learn what is actually happening across the stack — `claude-agent-acp`, the ACP SDK, and the Claude Agent SDK — before deciding how to restore dry-run behavior. A later branch will address the fix.

Code evidence already in hand for the current state:
- `node_modules/@agentclientprotocol/claude-agent-acp/dist/tools.js:377` — `case "Edit": // Edit is handled in hooks`
- `…/dist/acp-agent.js:401` — `case "files_persisted":` now exists; behavior is what we want to observe
- `…/dist/acp-agent.js:1147–1151` and `:1661` — `PostToolUse` hook owns Edit/Write side effects
- `resources/acp/index.js:127` — comment `// Hardcoded dry-run: acknowledge write requests without mutating disk.` now reflects a path the agent no longer takes

## Goal

Produce readable, per-run EDN trace files that record every coerced ACP callback (sessionUpdate, readTextFile, writeTextFile, requestPermission) for three representative scenarios (read / write-new / edit-existing), executed against the real `claude-agent-acp@0.29.2` subprocess. The trace files become the primary diagnostic artifact for understanding issue #211 and for later version comparisons.

## Non-goals

- Fixing the broken dry-run.
- Raw ndJSON wire capture, subprocess stderr capture, filesystem watchers (user chose coerced-callback-only).
- Automated cross-version diffing harness (user chose current-version coverage only).
- Production runtime capture (user chose test-only).

## Design

### Architecture

One test-only event recorder subscribes to every existing coerced callback in `main/effects/acp.cljs`, tags each event with a monotonic timestamp + kind, and persists the full chronological stream to `target/acp-traces/<scenario>-<ISO8601>.edn` at the end of every scenario run. Assertions shift to observation-first invariants; the trace file carries the detail.

### Components

**A. `src/gremllm/main/effects/acp.cljs` — minimal API addition**
Add an `on-read` tap to `initialize`, paralleling existing `on-permission` / `on-write`. The `read-text-file` handler stays the same; when `on-read` is provided we wrap it so the recorder sees each call. No behavior change when `on-read` is nil.

**B. `test/gremllm/main/effects/acp_trace.cljs` — new namespace (test support)**
Pure helpers + thin shell:

- `(make-recorder)` → `{:events atom :on-session-update fn :on-read fn :on-write fn :on-permission fn}`; each tap appends `{:ts <monotonic-ms> :kind :session-update|:read|:write|:permission :payload <coerced-map>}` to `events` in arrival order (single atom; interleaving is the point).
- `(write-trace! dir scenario events metadata)` serializes one EDN map to `target/acp-traces/<scenario>-<ISO8601>.edn` via `pr-str`. Metadata includes versions (read from the three `package.json`s), prompt text, workspace path, and `:result {:stop-reason …}` when available.
- No assertions here. Pure observation.

**C. `test/gremllm/main/effects/acp_integration_test.cljs` — three scenarios**

- `test-live-read-only` — prompt: "Summarize the first paragraph of the linked document." Expect only `:session-update` and `:read` event kinds; empty `:write` and `:permission` buckets.
- `test-live-write-new-file` — prompt: "Create a new file called `notes.md` in the workspace with content 'hello'." Observation: does the Write tool trigger a `:write` event, or is it absent just like Edit?
- `test-live-document-first-edit` — existing, refactored to use the shared recorder. Prompt unchanged. Asserts only `end_turn` + non-empty events; the failing write-file assertions become observations in the trace.

Each scenario writes its trace on the way out using `.finally`, so traces persist on both success and failure (verification item 5).

**D. `.gitignore`** — add `target/acp-traces/`.

### Data flow

```
scenario setup
  ├─ make-recorder → {events-atom, taps}
  ├─ acp/initialize on-session-update on-read on-write on-permission
  ├─ acp/new-session cwd
  └─ acp/prompt …
        │
        ▼
  [claude-agent-acp subprocess emits sessionUpdates, maybe readTextFile, maybe requestPermission, maybe writeTextFile]
        │
        ▼  each coerced callback
  recorder appends to single events-atom with :ts / :kind / :payload
        │
        ▼  .then (or .catch) resolves
  minimal assertions  →  write-trace!  →  acp/shutdown  →  done
```

### Trace file format (EDN)

One file per scenario run. Example shape:

```clojure
{:scenario   "document-first-edit"
 :started-at "2026-04-18T14:22:03.417Z"
 :versions   {:claude-agent-acp "0.29.2"
              :acp-sdk          "0.19.x"
              :gremllm          "0.6.0"}
 :prompt     "Read the linked document. Do not plan…"
 :doc-path   "/tmp/gremllm-test-…/gremllm-launch-log.md"
 :events     [{:ts 0   :kind :session-update :payload {…}}
              {:ts 42  :kind :read           :payload {:path "…" :line nil :limit nil}}
              {:ts 118 :kind :session-update :payload {…tool-call-update with diff…}}
              …]
 :result     {:stop-reason "end_turn"}}
```

EDN over JSONL: the project is Clojure-first, `schema.codec` already produces keywordized maps, and a single top-level map holds metadata and events together.

## Verification

1. `npm run test:integration` runs all three scenarios against a real subprocess and exits cleanly (requires `ANTHROPIC_API_KEY`).
2. `target/acp-traces/` contains three `.edn` files after the run, each with populated `:events` and non-empty `:versions`.
3. In the `document-first-edit` trace: count of `:write` events is zero (or surprisingly nonzero, which would itself be news), and `:session-update` entries with tool-call diffs are present. This is the diagnostic payload issue #211 is asking for.
4. The `read-only` trace contains only `:session-update` + `:read` kinds; the `write-new-file` trace exposes whether Write still routes through `writeTextFile` or bypasses like Edit.
5. Run one scenario with `ANTHROPIC_API_KEY` unset — the trace file still lands on disk (partial event stream plus captured error), proving the recorder persists on failure.

## Critical files

- `src/gremllm/main/effects/acp.cljs` — add `on-read` arity; wrap `read-text-file` when present. Reuses existing `make-permission-callback` / `make-write-callback` patterns.
- `test/gremllm/main/effects/acp_trace.cljs` — new.
- `test/gremllm/main/effects/acp_integration_test.cljs` — refactor existing test, add two more.
- `.gitignore` — ignore `target/acp-traces/`.

## Existing utilities to reuse

- `gremllm.schema.codec/acp-session-update-from-js`, `acp-permission-request-from-js` — already produce the keywordized payloads the recorder will store.
- `make-permission-callback` / `make-write-callback` in `main/effects/acp.cljs` — pattern to mirror for `on-read`.
- `acp-actions/prompt-content-blocks` — used by existing test to build the `resource_link` prompt.
- `resources/gremllm-launch-log.md` — existing fixture document for the edit scenario.

## Implementation order

1. Add `on-read` tap support to `main/effects/acp.cljs`.
2. Create `test/gremllm/main/effects/acp_trace.cljs` with recorder + writer.
3. Refactor `test-live-document-first-edit` to use the recorder; confirm trace file lands on disk.
4. Add `test-live-read-only` scenario.
5. Add `test-live-write-new-file` scenario.
6. Add `target/acp-traces/` to `.gitignore`.
7. Run `npm run test:integration`, inspect traces, confirm verification items 1–5.
