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

Extract a new sub-namespace `gremllm.main.effects.acp.permission` that owns the
registry, the tool-name index, the Promise builder, and a `make-resolve-permission`
factory. `effects/acp.cljs` shrinks back to connection lifecycle, session callbacks,
and file I/O.

Rejected alternatives:
- Promoting permissions to a top-level domain (`main/effects/permission.cljs`) — no second
  caller exists; speculative per CLAUDE.md "resist adding abstractions until they prove
  their worth." Naming the sub-namespace "ACP permission" is honest about today.
- Moving only the registry — leaves codec, decision branching, and Promise construction
  interleaved in the closure. Doesn't address the smell that motivated the issue.
- Exposing a smaller `await-user-decision` helper while keeping the closure — the caller
  still owns codec + decision + tap-vs-not branching, so the closure stays bloated.

## Naming

Names follow a hybrid rule: **SDK vocabulary at the boundary, domain vocabulary
inside**.

- The callback factory and the tap that fires for every SDK request mirror ACP
  words: `make-resolve-permission` matches the SDK's `resolvePermission`;
  `:on-permission-request` covers all incoming ACP requests.
- Internal state, the IPC-facing op, and the tap that fires only when we defer
  to the user lean domain: the atom holds `awaiting-user-decision`, the
  renderer's accept/reject lands in `record-decision!`, the deferral tap is
  `:on-awaiting-user-decision`.

This avoids two failure modes: pure ACP framing forces every reader to
translate "pending permission" into "user decision" at each callsite; pure
domain framing hides that this file IS the SDK callback (a name like
`make-permission-handler` won't lead a reader back to `resolvePermission`).

| Concept                       | Name                                       | Layer  |
|-------------------------------|--------------------------------------------|--------|
| SDK callback factory          | `make-resolve-permission`                  | SDK    |
| Tap on every SDK request      | `:on-permission-request`                   | SDK    |
| Tap on deferred-to-user       | `:on-awaiting-user-decision`               | Domain |
| Resolvers awaiting the user   | `awaiting-user-decision` (atom)            | Domain |
| Tool-name index               | `tool-name-by-id` (atom)                   | Domain |
| Seed tool-name index          | `track-tool-name!`                         | Domain |
| Submit user choice (IPC)      | `record-decision!`                         | Domain |
| Reset on shutdown             | `clear!`                                   | —      |
| Snapshot for tests            | `awaiting-snapshot`                        | Domain |
| Register resolver (private)   | `register-resolver!`                       | —      |
| Build deferred Promise (priv) | `await-user-decision`                      | Domain |
| Invoke observer safely (priv) | `fire-tap!`                                | —      |

## Target architecture

```
                              ┌─────────────────────────────────────────────────┐
                              │ schema/codec/acp/permission.cljs                │  pure
                              │   resolve-permission (→ tagged decision)        │  (unchanged)
                              │   enrich-permission-params                      │
                              │   remember-tool-name                            │
                              └────────────────▲────────────────────────────────┘
                                               │
                              ┌────────────────┴────────────────────────────────┐
                              │ effects/acp/permission.cljs   (NEW)             │
                              │                                                 │
                              │  Public:                                        │
                              │    make-resolve-permission   ◄── ACP init       │
                              │    track-tool-name!          ◄── ACP session    │
                              │    record-decision!          ◄── IPC handler    │
                              │    awaiting-snapshot         ◄── tests          │
                              │    clear!                    ◄── ACP shutdown   │
                              │                                                 │
                              │  Private state:                                 │
                              │    awaiting-user-decision atom (resolvers/id)   │
                              │    tool-name-by-id atom                         │
                              │                                                 │
                              │  Private helpers:                               │
                              │    register-resolver!                           │
                              │    await-user-decision                          │
                              │    fire-tap!                                    │
                              └────────────────▲────────────────────────────────┘
                                               │
                              ┌────────────────┴────────────────────────────────┐
                              │ effects/acp.cljs   (slimmed)                    │
                              │   connection lifecycle                          │
                              │   session-cb wiring                             │
                              │   read-text-file                                │
                              │   initialize/shutdown                           │
                              └─────────────────────────────────────────────────┘
```

## Files to change

### New: `src/gremllm/main/effects/acp/permission.cljs`

```clojure
(ns gremllm.main.effects.acp.permission
  "Owns the SDK's resolvePermission seam: tool-name tracking, the registry of
   resolvers awaiting user input, and the callback the SDK invokes for each
   permission request."
  (:require [gremllm.schema.codec.acp :as acp-codec]
            [gremllm.schema.codec.acp.permission :as acp-permission]))

;; Tool-name index seeded from session updates. ACP's RequestPermission
;; payload omits tool_name; see schema.codec.acp.permission for the workaround.
(defonce ^:private tool-name-by-id (atom {}))

;; Resolvers awaiting the user's accept/reject, keyed by ACP tool-call-id.
(defonce ^:private awaiting-user-decision (atom {}))

(defn track-tool-name!
  "Seed the tool-name index from a coerced session update.
   Called by the ACP shell's onSessionUpdate handler."
  [coerced-session-update]
  (swap! tool-name-by-id acp-permission/remember-tool-name coerced-session-update))

(defn record-decision!
  "Fire the registered resolver for tool-call-id with the user's option-id.
   No-op when no resolver is registered. Called from the IPC handler when
   the renderer reports the user's accept/reject."
  [tool-call-id option-id]
  (when-let [resolver (get @awaiting-user-decision tool-call-id)]
    (swap! awaiting-user-decision dissoc tool-call-id)
    (resolver option-id)
    nil))

(defn awaiting-snapshot
  "Snapshot of resolvers currently awaiting the user. For tests/inspection."
  []
  @awaiting-user-decision)

(defn clear!
  "Reset all permission state. Called from ACP shutdown."
  []
  (reset! tool-name-by-id {})
  (reset! awaiting-user-decision {}))

(defn- register-resolver! [tool-call-id resolver]
  (when (contains? @awaiting-user-decision tool-call-id)
    (js/console.warn "[ACP] replacing pending permission resolver for" tool-call-id))
  (swap! awaiting-user-decision assoc tool-call-id resolver))

(defn- await-user-decision
  "Register a resolver under tool-call-id and return a Promise that resolves
   to a JS permission outcome once record-decision! is called for that id."
  [tool-call-id]
  (js/Promise.
    (fn [resolve _reject]
      (register-resolver! tool-call-id
                          (fn [option-id]
                            (resolve (acp-codec/acp-permission-outcome-to-js
                                       {:outcome {:outcome "selected" :option-id option-id}})))))))

(defn- fire-tap! [f arg label]
  (when f
    (try (f arg)
         (catch :default e
           (js/console.error "ACP" label "tap failed" e)))))

(defn make-resolve-permission
  "Build the SDK-shaped resolvePermission callback. Taps are optional observers:
     :on-permission-request      fired for every request (immediate or deferred)
     :on-awaiting-user-decision  fired only when the resolver defers to the user"
  [{:keys [on-permission-request on-awaiting-user-decision]}]
  (fn [^js raw-params session-cwd]
    (try
      (let [enriched (->> raw-params
                          acp-codec/acp-permission-request-from-js
                          (acp-permission/enrich-permission-params @tool-name-by-id))
            decision (acp-permission/resolve-permission enriched session-cwd)]
        (fire-tap! on-permission-request enriched "on-permission-request")
        (case (:resolution decision)
          :immediate
          (acp-codec/acp-permission-outcome-to-js {:outcome (:outcome decision)})

          :deferred
          (let [p (await-user-decision (:tool-call-id decision))]
            (fire-tap! on-awaiting-user-decision enriched "on-awaiting-user-decision")
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
;; Note: initialize's signature destructures the renamed kwargs:
;;   [{:keys [on-session-update on-permission-request on-awaiting-user-decision]}]
(let [session-cb   (fn [raw-params]
                     (try
                       (permission/track-tool-name!
                         (acp-codec/acp-session-update-from-js raw-params))
                       (catch :default _))
                     (when on-session-update (on-session-update raw-params)))
      resolve-cb   (permission/make-resolve-permission
                     {:on-permission-request     on-permission-request
                      :on-awaiting-user-decision on-awaiting-user-decision})
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
    (acp-permission/record-decision! tool-call-id option-id)))   ; was acp-effects/resolve-pending-permission!
```

Add require: `[gremllm.main.effects.acp.permission :as acp-permission]`.

The Nexus effect key `:acp.effects/resolve-permission` is unchanged — it is the
renderer-facing contract and out of scope for this rename.

### Modified: `src/gremllm/main/core.cljs:118`

The kwarg rename ripples to the single call site that wires the tap. One line:

```clojure
(acp-effects/initialize
  {:on-session-update            (acp-effects/make-session-update-callback store nil)
   :on-awaiting-user-decision    ; was :on-pending-permission
   (fn [enriched]
     (nxr/dispatch store {} [[:acp.events/permission-pending enriched]]))})
```

The Nexus action `:acp.events/permission-pending` keeps its name — also a
renderer-facing IPC contract, out of scope for this rename.

### Modified: tests

Move closure-level and registry-level tests out of `test/gremllm/main/effects/acp_test.cljs`
into a new `test/gremllm/main/effects/acp/permission_test.cljs`:

- `test-resolve-cb-returns-promise-for-deferred` — retarget to
  `permission/make-resolve-permission` directly instead of going through
  `acp/initialize`. Removes the `with-redefs` over `create-connection`; just
  construct the callback and call it.
- `test-pending-permission-registry` — retarget to register-via-callback and
  `permission/record-decision!` / `permission/awaiting-snapshot`. (Direct
  `register-resolver!` is now private; test through the public seam by invoking
  the callback for a deferred case, or expose a test-only path. Prefer the
  former — tests the actual contract.)
- `test-permission-tap-failure-does-not-replace-outcome` — retarget to
  `make-resolve-permission` with a throwing `:on-permission-request` tap.

Remaining tests in `effects/acp_test.cljs` cover only lifecycle, session forwarding, and
file I/O — matches the slim-down.

## Why this satisfies the goals

- **Modelarity:** "ACP permission" is now a namespace. Reading
  `effects/acp/permission.cljs` top to bottom is the answer to "what is the ACP permission
  system?" The phrase has a home.
- **Concern separation:** Inside `make-resolve-permission`, the three concerns from the
  issue map to three lines: codec/enrichment is a `->>` thread, decision is one fn call,
  branching is a `case`. The Promise builder and tap helper are named private fns.
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
  giving `make-resolve-permission` a clean home, but adding new kinds is its own change to
  `schema/codec/acp/permission/resolve-permission`.
- Renaming the renderer/IPC seam — `:acp.events/permission-pending` (action),
  `acp:permission-pending` (IPC channel), and `:acp.effects/resolve-permission`
  (Nexus effect) all keep current names. They are renderer-facing contracts and
  the naming pass deliberately stops at the main-process boundary.

## Critical files

- `src/gremllm/main/effects/acp.cljs` (modify; ~60 LoC removed)
- `src/gremllm/main/effects/acp/permission.cljs` (new; ~90 LoC)
- `src/gremllm/main/actions.cljs` (1-line change + require)
- `src/gremllm/main/core.cljs` (1-line change — `:on-pending-permission` kwarg → `:on-awaiting-user-decision`)
- `test/gremllm/main/effects/acp_test.cljs` (remove 3 tests)
- `test/gremllm/main/effects/acp/permission_test.cljs` (new; migrated tests)
- `src/gremllm/schema/codec/acp/permission.cljs` (unchanged — referenced)
- `src/gremllm/renderer/actions/topic.cljs` (unchanged — referenced)
