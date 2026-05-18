# Decomplect ACP permission resolution

Issue: [#257](https://github.com/Quantisan/gremllm/issues/257)
Date: 2026-05-18

## Context

PR #256 landed a deferred-permission flow inside `src/gremllm/main/effects/acp.cljs`. The
`resolve-cb` closure at `effects/acp.cljs:164-197` interleaves three concerns inside one
34-line block:

1. **Boundary codec** — `acp-permission-request-from-js`, `enrich-permission-params`,
   `acp-permission-outcome-to-js`. JS ↔ CLJS shape translation.
2. **Domain decision branching** — calls `acp-permission/resolve-permission` and branches
   on `:immediate` / `:deferred`. The pure decision is already isolated in
   `schema/codec/acp/permission`; the branching on its tagged result lives in the closure.
3. **SDK plumbing** — `js/Promise.` construction, stashing the resolver in
   `pending-permissions`, firing taps, and producing the SDK-required return shape (JS
   value vs. JS Promise).

The `pending-permissions` registry (`effects/acp.cljs:41-68`) and the per-connection
`tool-names` atom (`effects/acp.cljs:157`) also live in the SDK shell. The phrase "ACP
permission" has no obvious namespace today — it spans the closure, two atoms, an IPC
handler, and a renderer action.

This issue is the artifact-side decision; future deferral cases (fetch on non-WebSearch,
other kinds) will replicate the Promise/stash/tap pattern unless extracted.

### Hard constraint

The Claude Agent SDK's `resolvePermission` callback must synchronously return either a JS
value or a JS Promise. Orchestration cannot become "dispatch async into Nexus and come
back" — whatever runs inside the callback must produce the answer (or the Promise) on the
same tick. This rules out reshaping the resolver as a Nexus action.

## Decision

Extract a new sub-namespace `gremllm.main.effects.acp.permission` that owns the registry,
the tool-name tracker, the Promise builder, and a `make-resolve-cb` factory.
`effects/acp.cljs` shrinks back to connection lifecycle, session callbacks, and file I/O.

Rejected alternatives:
- Promoting permissions to a top-level domain (`main/effects/permission.cljs`) — no second
  caller exists; speculative per CLAUDE.md "resist adding abstractions until they prove
  their worth." Naming the sub-namespace "ACP permission" is honest about today.
- Moving only the registry — leaves codec, decision branching, and Promise construction
  interleaved in the closure. Doesn't address the smell that motivated the issue.
- Exposing a smaller `await-user-decision` helper while keeping the closure — the caller
  still owns codec + decision + tap-vs-not branching, so the closure stays bloated.

## Target architecture

```
                              ┌─────────────────────────────────────────┐
                              │ schema/codec/acp/permission.cljs        │  pure
                              │   resolve-permission (→ tagged decision)│  (unchanged)
                              │   enrich-permission-params              │
                              │   remember-tool-name                    │
                              └────────────────▲────────────────────────┘
                                               │
                              ┌────────────────┴────────────────────────┐
                              │ effects/acp/permission.cljs   (NEW)     │
                              │                                         │
                              │  Public:                                │
                              │    make-resolve-cb     ◄── ACP init     │
                              │    track-tool-name!    ◄── ACP session  │
                              │    resolve!            ◄── IPC handler  │
                              │    snapshot            ◄── tests        │
                              │    clear!              ◄── ACP shutdown │
                              │                                         │
                              │  Private:                               │
                              │    pending atom (resolvers by tc-id)    │
                              │    tool-names atom                      │
                              │    stash!                               │
                              │    await-user-decision                  │
                              │    fire-tap!                            │
                              └────────────────▲────────────────────────┘
                                               │
                              ┌────────────────┴────────────────────────┐
                              │ effects/acp.cljs   (slimmed)            │
                              │   connection lifecycle                  │
                              │   session-cb wiring                     │
                              │   read-text-file                        │
                              │   initialize/shutdown                   │
                              └─────────────────────────────────────────┘
```

## Files to change

### New: `src/gremllm/main/effects/acp/permission.cljs`

```clojure
(ns gremllm.main.effects.acp.permission
  "Owns the SDK's resolvePermission seam: tool-name tracking, the pending-resolver
   registry, and the callback the SDK invokes for each permission request."
  (:require [gremllm.schema.codec.acp :as acp-codec]
            [gremllm.schema.codec.acp.permission :as acp-permission]))

;; Tool-name registry seeded from session updates. ACP's RequestPermission
;; payload omits tool_name; see schema.codec.acp.permission for the workaround.
(defonce ^:private tool-names (atom {}))

;; Resolvers awaiting user accept/reject, keyed by ACP tool-call-id.
(defonce ^:private pending (atom {}))

(defn track-tool-name!
  "Seed the tool-name registry from a coerced session update.
   Called by the ACP shell's onSessionUpdate handler."
  [coerced-session-update]
  (swap! tool-names acp-permission/remember-tool-name coerced-session-update))

(defn resolve!
  "Fire the registered resolver for tool-call-id with option-id.
   No-op when no resolver is registered. Called from the IPC handler when
   the renderer reports the user's accept/reject."
  [tool-call-id option-id]
  (when-let [resolver (get @pending tool-call-id)]
    (swap! pending dissoc tool-call-id)
    (resolver option-id)
    nil))

(defn snapshot
  "Snapshot of pending resolvers. For tests/inspection."
  []
  @pending)

(defn clear!
  "Reset all permission state. Called from ACP shutdown."
  []
  (reset! tool-names {})
  (reset! pending {}))

(defn- stash! [tool-call-id resolver]
  (when (contains? @pending tool-call-id)
    (js/console.warn "[ACP] replacing pending permission resolver for" tool-call-id))
  (swap! pending assoc tool-call-id resolver))

(defn- await-user-decision
  "Stash a resolver under tool-call-id and return a Promise that resolves to a
   JS permission outcome once resolve! is called for that id."
  [tool-call-id]
  (js/Promise.
    (fn [resolve _reject]
      (stash! tool-call-id
              (fn [option-id]
                (resolve (acp-codec/acp-permission-outcome-to-js
                           {:outcome {:outcome "selected" :option-id option-id}})))))))

(defn- fire-tap! [f arg label]
  (when f
    (try (f arg)
         (catch :default e
           (js/console.error "ACP" label "tap failed" e)))))

(defn make-resolve-cb
  "Build the SDK-shaped resolvePermission callback. Taps are optional observers:
     :on-permission         fired for every request (immediate or deferred)
     :on-pending-permission fired only when the resolver defers to the user"
  [{:keys [on-permission on-pending-permission]}]
  (fn [^js raw-params session-cwd]
    (try
      (let [enriched (->> raw-params
                          acp-codec/acp-permission-request-from-js
                          (acp-permission/enrich-permission-params @tool-names))
            decision (acp-permission/resolve-permission enriched session-cwd)]
        (fire-tap! on-permission enriched "on-permission")
        (case (:resolution decision)
          :immediate
          (acp-codec/acp-permission-outcome-to-js {:outcome (:outcome decision)})

          :deferred
          (let [p (await-user-decision (:tool-call-id decision))]
            (fire-tap! on-pending-permission enriched "on-pending-permission")
            p)))
      (catch :default e
        (js/console.error "ACP permission resolve failed" e "raw params:" raw-params)
        #js {:outcome #js {:outcome "cancelled"}}))))
```

### Modified: `src/gremllm/main/effects/acp.cljs`

Remove (now in the new ns):
- `pending-permissions` atom (`:41-45`)
- `stash-pending-permission!` (`:47-54`)
- `resolve-pending-permission!` (`:56-63`)
- `pending-permission-snapshot` (`:65-68`)
- The `tool-names` atom and `remember-tool-name` swap in `session-cb` (`:157, :158-163`)
- The `resolve-cb` closure (`:164-197`)

Replace `initialize`'s let-bindings (`:151-207`) with:

```clojure
(let [session-cb   (fn [raw-params]
                     (try
                       (permission/track-tool-name!
                         (acp-codec/acp-session-update-from-js raw-params))
                       (catch :default _))
                     (when on-session-update (on-session-update raw-params)))
      resolve-cb   (permission/make-resolve-cb
                     {:on-permission         on-permission
                      :on-pending-permission on-pending-permission})
      ^js result   (create-connection
                     #js {:onSessionUpdate   session-cb
                          :onReadTextFile    read-text-file
                          :resolvePermission resolve-cb})
      ...]
  ...)
```

Update `shutdown` (`:246-254`):

```clojure
(defn shutdown []
  (permission/clear!)            ; replaces (reset! pending-permissions {})
  ...)
```

Add require: `[gremllm.main.effects.acp.permission :as permission]`.

### Modified: `src/gremllm/main/actions.cljs:93-95`

```clojure
(nxr/register-effect! :acp.effects/resolve-permission
  (fn [_ _ tool-call-id option-id]
    (acp-permission/resolve! tool-call-id option-id)))   ; was acp-effects/resolve-pending-permission!
```

Add require: `[gremllm.main.effects.acp.permission :as acp-permission]`.

### Modified: tests

Move closure-level and registry-level tests out of `test/gremllm/main/effects/acp_test.cljs`
into a new `test/gremllm/main/effects/acp/permission_test.cljs`:

- `test-resolve-cb-returns-promise-for-deferred` — retarget to `permission/make-resolve-cb`
  directly instead of going through `acp/initialize`. Removes the `with-redefs` over
  `create-connection`; just construct the callback and call it.
- `test-pending-permission-registry` — retarget to `permission/stash!`-via-callback and
  `permission/resolve!` / `permission/snapshot`. (Direct `stash!` is now private; test
  through the public seam by invoking the callback for a deferred case, or expose a
  test-only path. Prefer the former — tests the actual contract.)
- `test-permission-tap-failure-does-not-replace-outcome` — retarget to `make-resolve-cb`
  with a throwing `:on-permission` tap.

Remaining tests in `effects/acp_test.cljs` cover only lifecycle, session forwarding, and
file I/O — matches the slim-down.

## Why this satisfies the goals

- **Modelarity:** "ACP permission" is now a namespace. Reading
  `effects/acp/permission.cljs` top to bottom is the answer to "what is the ACP permission
  system?" The phrase has a home.
- **Concern separation:** Inside `make-resolve-cb`, the three concerns from the issue map
  to three lines: codec/enrichment is a `->>` thread, decision is one fn call, branching
  is a `case`. The Promise builder and tap helper are named private fns.
- **Files as boundaries:** `effects/acp.cljs` drops back to a single public contract —
  ACP connection lifecycle. The new file has its own single contract — produce the SDK's
  resolvePermission callback and let callers fire stashed resolvers.
- **YAGNI:** No top-level `permission` domain manufactured for a hypothetical second
  caller. If a second origin appears later, lift the file by rename + update requires.

## Verification

1. `npm run test:all` — unit + integration suites pass. The migrated permission tests
   exercise the same seams (deferred Promise resolution, registry behavior, tap-failure
   isolation) against the new public API.
2. Manual ACP flow in `npm run dev`:
   - Open a workspace, send a prompt that triggers an in-workspace edit (e.g. ask the
     agent to modify `document.md`).
   - Confirm a pending diff appears in the document panel.
   - Click Accept — confirm the edit applies and the diff clears.
   - Send another edit prompt, click Reject — confirm the edit does not apply.
3. Read-only flow (read tool): confirm it auto-allows without a pending-diff card.
4. Out-of-workspace edit: prompt the agent to edit `/tmp/foo.md`; confirm it is
   auto-rejected (preserves the workspace-root guard).

## Out of scope (deliberately deferred)

- `AcpSession` schema split — transient keys `:resolved-tool-calls` and
  `:pending-permission-options` still leak into `[:session]`. Track separately
  (`src/gremllm/schema.cljs:170` TODO).
- Inbound-routing reverse lookup — events still dispatch to active topic instead of source
  topic (`src/gremllm/renderer/state/topic.cljs:63-78` TODO).
- Future deferral kinds (fetch on non-WebSearch, etc.) — this refactor enables them by
  giving `make-resolve-cb` a clean home, but adding new kinds is its own change to
  `schema/codec/acp/permission/resolve-permission`.

## Critical files

- `src/gremllm/main/effects/acp.cljs` (modify; ~60 LoC removed)
- `src/gremllm/main/effects/acp/permission.cljs` (new; ~90 LoC)
- `src/gremllm/main/actions.cljs` (1-line change + require)
- `test/gremllm/main/effects/acp_test.cljs` (remove 3 tests)
- `test/gremllm/main/effects/acp/permission_test.cljs` (new; migrated tests)
- `src/gremllm/schema/codec/acp/permission.cljs` (unchanged — referenced)
- `src/gremllm/main/core.cljs` (unchanged — referenced)
- `src/gremllm/renderer/actions/topic.cljs` (unchanged — referenced)
