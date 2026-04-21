# Plan: Simplify `spike/in-process-acp-host` before merge

**Date:** 2026-04-22
**Branch:** `spike/in-process-acp-host`
**Scope:** pre-merge cleanup; MVP-level simplification, deletion-favored.

## Context

The spike replaced subprocess-spawned ACP with an in-process host (paired `TransformStream`s, `ClientSideConnection` ↔ `AgentSideConnection` ↔ `ClaudeAcpAgent`) and cleared its decision-gate criteria. Before merge, the internal API carries three kinds of extra weight:

1. **Defensive state** — `initialize-in-flight` exists to guard a shutdown-mid-init race that doesn't occur at MVP (topic-switch shutdown is sequential with user action; app-quit shutdown doesn't race init).
2. **Policy logic in JS** — `permission.js`, `rememberToolName`, `enrichPermissionParams`, and the `__test__` export ritual are pure functions carrying cwd-awareness and tool-name memoization. They belong in CLJS alongside `schema.codec`.
3. **One-def namespace** — `effects/acp/claude_adapter.cljs` holds a single `session-meta` literal. The docstring is load-bearing, but a dedicated namespace is heavier than the decomplection warrants. The original spike plan (step 5) placed this at the prompt call site.

Shutdown is a real operation (topic-switch is near-term work), so it stays. The cleanup is about dropping the *second* atom and the `cond->` branching it forces into every shutdown path.

## Non-goals

- Not moving ACP state into the Nexus store. The `state` atom is an imperative-shell singleton holding a connection handle — same shape as the old `:subprocess` slot. Nexus is for business state.
- Not converting `effects.acp` public fns into registered effects. The three wrapper effects in `actions.cljs` are already thin and readable.
- Not addressing carried TODOs: unbounded `toolNamesByCallId`, `TransformStream` error surfacing, read auto-approval allowlist. Separate work.

## Changes

### 1. Single atom for ACP lifecycle

`src/gremllm/main/effects/acp.cljs` — drop `initialize-in-flight`. Single `state` atom:

```clojure
(defonce ^:private state (atom nil))
;; @state is nil, or:
;;   {:connection    <or-nil-until-init-resolves>
;;    :dispose-agent <fn>
;;    :ready         <init-promise>}
```

`initialize` — idempotent via `(:ready @state)` early return. On factory creation, seed `{:connection nil :dispose-agent ... :ready ...}` immediately (dispose-agent is synchronously available from the JS factory). On init resolution, `(swap! state assoc :connection connection)`. On init rejection, dispose then `(reset! state nil)` and rethrow.

`shutdown` — collapse to:
```clojure
(defn shutdown []
  (when-let [{:keys [dispose-agent]} @state]
    (reset! state nil)
    (dispose-agent)))
```

Returns the dispose promise (or nil → renderer coerces to `Promise.resolve` at the wrapper). Topic-switch awaits this before dispatching the next `initialize`.

`start-connection!` — retains its init-failure dispose-then-rethrow chain. No `.finally` state reset needed (error branch handles it; success branch writes `:connection`).

### 2. JS module shrinks to transport-only

`src/js/acp/index.js` — becomes ~40 lines. Keeps:
- Paired `TransformStream`s.
- `ClientSideConnection` / `AgentSideConnection` construction.
- `agentReady` promise + `disposeAgent` (chains close on streams).
- `newSession`/`unstable_resumeSession` interception for cwd capture (local `sessionCwdMap` closure — genuinely connection-local, not Nexus-worthy).
- Five callback slots exposed to CLJS: `onSessionUpdate`, `onRequestPermission`, `onReadTextFile`, `onWriteTextFile`, and a new `resolvePermission` that CLJS supplies as a pure fn.

Drops:
- `rememberToolName`, `enrichPermissionParams`, `toolNamesByCallId` Map (moved to CLJS-provided callback closure).
- `__test__` export block.

Delete `src/js/acp/permission.js`.

### 3. Permission policy + tool-name memoization in CLJS

New namespace: `gremllm.schema.codec.acp-permission` (or sibling under `schema.codec`). Pure fns:
- `requested-tool-name` (port of JS version)
- `requested-path` (port of JS version)
- `within-root?` (port)
- `resolve-permission` — takes `{:params ... :session-cwd ...}`, returns outcome map
- `remember-tool-name` / `enrich-permission-params` — pure transforms over a CLJS map held in a closure atom

`effects/acp.cljs` — builds a closure holding a `(atom {})` for tool-names-by-call-id (per-connection scope; cleared with the state atom on shutdown). The closure wires:
- `onSessionUpdate` tap → `remember-tool-name` into the closure atom → dispatch coerced event.
- `onRequestPermission` tap → `enrich-permission-params` via closure atom → call `on-permission` tap → return `resolve-permission` outcome synchronously to JS.

Move the policy tests (`test-permission-resolver-policy`, `test-permission-requested-tool-name`, `test-remember-and-enrich-tool-name`, `test-enrich-without-tracked-tool-name`) from `acp_test.cljs` JS-interop paths to pure CLJS tests against the new namespace.

### 4. Inline `claude-adapter/session-meta`

Delete `src/gremllm/main/effects/acp/claude_adapter.cljs`. Move the `session-meta` `#js` literal (with its full docstring-comment intact) into `gremllm.main.effects.acp` as a `^:private def`, used inline by `new-session`/`resume-session`. This is the pragmatic MVP shape — one def, one comment, one use site.

(The original spike plan step 5 targeted `main.actions.acp` prompt construction. Current code paths build session options in `effects.acp`, not prompt construction — keep the def co-located with its actual call sites.)

## Files

- `src/gremllm/main/effects/acp.cljs` — single atom, inlined session-meta, closure-held tool-names, CLJS callback wiring.
- `src/js/acp/index.js` — transport-only, callbacks accepted as opts, no `__test__`.
- `src/js/acp/permission.js` — **deleted**.
- `src/gremllm/main/effects/acp/claude_adapter.cljs` — **deleted**.
- `src/gremllm/schema/codec/acp_permission.cljs` — **new**, pure policy.
- `test/gremllm/main/effects/acp_test.cljs` — policy tests migrate out; keep lifecycle tests (`test-initialize-wiring`, `test-callback-fires-and-coerces-diffs`, `test-session-and-prompt-delegation`, `test-lifecycle-guardrails`, `test-initialize-failure-does-not-poison-state`, `test-start-connection-catch-chains-rethrow-after-dispose`, `test-shutdown-returns-promise-that-awaits-dispose`, `test-slice-content-by-lines`, `test-read-text-file`).
- `test/gremllm/schema/codec/acp_permission_test.cljs` — **new**, hosts the migrated policy tests in pure CLJS.
- `test/gremllm/main/effects/acp_integration_test.cljs` — unchanged (live-ACP smoke stays intact).

Reused functions worth referencing in implementation:
- `gremllm.schema.codec/acp-session-update-from-js`, `acp-permission-request-from-js` — continue to be the coercion boundary; policy tests use these to fixture params.
- `gremllm.main.effects.acp/slice-content-by-lines` — unchanged; keep its pure-fn shape.

## Verification

1. **Unit tests green:** `npm run test` — lifecycle + new policy namespace.
2. **Integration smoke:** `npm run test:integration` — live-ACP `test-live-acp-happy-path`, `test-live-read-only`, `test-live-write-new-file`, `test-live-document-first-edit` all pass with traces intact.
3. **Dev mode end-to-end:** `npm run dev` — create topic, prompt, stream reply, inline pending-diff render, multi-turn, resume across reload. Matches the spike's step-1 acceptance.
4. **Packaged mode spot-check:** `npm run make`; launch `/Applications/Gremllm.app` from Finder in a clean shell; new session + prompt succeeds. Confirms the transport trim didn't regress the fuse/run-as-node path.
5. **Shutdown shape for topic-switch:** in the REPL, `(acp-effects/initialize ...)` then `(acp-effects/shutdown)` returns a promise that settles; re-initialize after that returns a fresh connection.
6. **Line-count check:** `git diff main..HEAD -- src/js/acp/ src/gremllm/main/effects/acp.cljs` — confirm net deletion.
