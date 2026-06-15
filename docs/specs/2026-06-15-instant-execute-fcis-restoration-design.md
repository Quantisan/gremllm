# Design: Restoring FCIS Purity with Nexus Instant-Execute

**Date:** 2026-06-15
**Status:** Exploratory — deferred. Gated on upstream Nexus releasing the `instant-execute` execution model.
**Related:**
- Upstream ADR: `nexus/doc/adr01-instantly-process-effects.md` (branch `instant-execute` at https://github.com/cjohansen/nexus)
- Engineering rule: CLAUDE.md "Strict FCIS (Functional Core, Imperative Shell)"
- Touched code: [src/gremllm/renderer/actions.cljs](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions.cljs), [src/gremllm/renderer/actions/topic.cljs](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)

## Goal

Capture an opportunity, not a committed change: an upcoming Nexus execution-model change (`instant-execute`) removes the exact limitation that currently forces several pieces of pure decision logic to live in the imperative shell. When that change ships in a released Nexus, we can move that logic back into the functional core, which is what our Strict FCIS rule wants. This doc articulates the opportunity so it's actionable later; it deliberately stops short of a prescribed implementation.

## Background: the limitation and what changes

Today's Nexus (`2025.11.1`, the version pinned in `deps.edn`) uses a two-phase dispatch model:

1. Expand **all** actions recursively into a flat list of effects — every action expansion reads the **same** state snapshot, captured at dispatch start.
2. Execute the effects in order.

Consequence: an action expanded later in a dispatch **cannot** see state that an effect wrote earlier in the *same* dispatch. To read "what was just written," logic must be an **effect** (which can `@store` the live atom at execution time) rather than an **action** (which only receives the frozen snapshot).

The `instant-execute` model (upstream ADR-1) changes this: unbatched effects execute immediately during expansion, and subsequent action expansions see a state snapshot that includes those writes. The motivating upstream example — two update-style `inc` actions in one dispatch — yields `2` instead of `1`.

This matters for us specifically because **gremllm uses no `^:nexus/batch` metadata** on any effect (verified: no occurrences in `src/`). Per the ADR, a fully-batched system would observe no change; an unbatched one like ours sees effects flip from deferred to immediate. So the new read-your-own-writes behavior is fully available to us — and the workarounds it obviates are fully ours to retire.

## The pattern in our codebase

We have effects that are conceptually pure `(state, args) → effect-descriptions` decisions, demoted to the imperative shell *solely* to read state written earlier in the same dispatch. Two of them are self-documented as such. The signature is an effect that derefs the store and re-dispatches:

```clojure
(fn [{:keys [dispatch]} store ...] ... @store ... (dispatch ...))
```

A sweep for this signature (`@store` across `src/gremllm/renderer/` and `src/gremllm/main/`) found the renderer cases below. The `@store` reads in `src/gremllm/main/core.cljs` are IPC handlers in the imperative shell building dispatches from live state — correct FCIS, not workarounds, and out of scope here.

The `@store`-in-effect signature is the deliberate net for this opportunity. Arg-threaded and multi-dispatch data flows are *not* in scope: passing a value explicitly through action args, or sequencing across dispatches, is already good FCIS (explicit over implicit) and gains nothing from read-your-own-writes. The opportunity is specifically about logic forced *out* of the core to read live state, not about logic that already flows cleanly through it.

## Candidates

### Strong: the auto-save / save-topic chain

`topic/auto-save` is **already written as a pure** `(state, topic-id) → effects` function ([actions/topic.cljs:42](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)):

```clojure
(defn auto-save [state topic-id]
  (let [messages (when topic-id (topic-state/get-topic-field state topic-id :messages))
        excerpts (when topic-id (topic-state/get-topic-field state topic-id :excerpts))]
    (when (or (seq messages) (seq excerpts))
      [[:topic.effects/save-topic topic-id]])))
```

It is registered as an **effect** only to dodge the snapshot limitation ([actions.cljs:197](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions.cljs)):

```clojure
;; Auto-save effect - reads live state to check if messages exist before saving.
;; This must be an effect (not an action) to avoid stale state when called from async promises.
;; Actions receive immutable snapshots from dispatch time, but effects can @store for current state.
(nxr/register-effect! :topic.effects/auto-save
  (fn [{:keys [dispatch]} store topic-id]
    (when-let [effects (topic/auto-save @store topic-id)]
      (dispatch effects))))
```

The downstream `:topic.effects/save-topic` and `:topic.effects/save-active-topic` ([actions/topic.cljs:160, 173](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)) follow the same shape — they `@store` to read the topic, then dispatch a save promise — but their data dependencies differ, and the difference matters:

- **`auto-save` — confirmed, instant-execute-dependent.** All five call sites emit it *last*, after the effects that write the data it reads, in the same dispatch: `excerpt/add`, `excerpt/remove-excerpt`, `excerpt/clear-active` ([excerpt.cljs:35,44,51](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/excerpt.cljs)), `topic/finalize-turn` ([topic.cljs:57](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)), `messages/add-message` ([messages.cljs:13](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/messages.cljs)). A fresh snapshot under instant-execute equals what `@store` reads today — behavior-preserving, and it genuinely needs the new model.
- **`save-topic` — confirmed, instant-execute-dependent (via `auto-save`).** Its only structural caller is `auto-save` ([topic.cljs:49](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)) (plus `save-active-topic` below), so it rides `auto-save`'s same-dispatch ordering. As an action it reads the topic from a snapshot that, under instant-execute, includes the in-chain writes.
- **`save-active-topic` — convertible *today*, does NOT need instant-execute.** Its sole caller is a standalone menu/IPC dispatch ([core.cljs:39](/Users/paul/Projects/gremllm/src/gremllm/renderer/core.cljs)): `[[:topic.effects/save-active-topic]]` with nothing written before it in that dispatch. The `active-topic-id` it reads via `(get-active-topic-id @store)` was set in a *prior* dispatch, so a plain action would already see it in its dispatch-start snapshot. This is the same class as `init-session` below — it just happens to also be on the save chain. Its existing `;; TODO: should ... be an action?` comment ([topic.cljs:170](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)) can be resolved independent of the Nexus upgrade.

### Not a candidate: `:acp.effects/init-session`

This effect ([actions.cljs:227](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions.cljs)) carries an "Accidental impurity" comment and looks superficially like the same pattern, but investigation rules it out on two independent grounds:

1. **It is dead code.** A search across `.cljs` source, compiled JS, and `preload.js` found `:acp.effects/init-session` only at its own registration — it is dispatched from nowhere.
2. **Instant-execute would not be its unlock anyway.** The `acp-session-id` it reads is written in a **prior, separate dispatch**: main reads topics off disk → `"document:opened"` IPC → renderer's `document.actions/opened` → `restore-with-topics` runs `[:effects/save topic-state/topics-path topics]`. By the time anything could run `init-session`, that value is already settled in the store, so even a plain action would see it in its own dispatch-start snapshot. Its impurity comment ("…missing topic data saved by earlier effects **in the chain**") mis-describes the real flow (prior dispatch, not same chain).

**Disposition:** track `init-session` as a *separate, unrelated* cleanup — either wire it up or delete it (and remove the misleading comment) — after confirming it is genuinely unused versus half-built. It does not belong to this opportunity.

## Why this is worth doing

- **Restores Strict FCIS.** Business/decision logic ("should we save? what do we save?") returns to the functional core, where our engineering rules say it belongs.
- **Improves testability.** Pure actions are tested by asserting on returned effect vectors — no `@store`, no nested-dispatch plumbing, no async timing. Compare the current `actions_test.cljs` async-with-`setTimeout` shape against a synchronous `(is (= [...] (auto-save state id)))`.
- **Removes a layer of indirection.** Each converted effect drops its `@store` + nested `(dispatch …)` wrapper and simply *returns* effects.
- **Retires self-documented debt.** Two comments and a TODO currently exist only to explain a limitation that will no longer exist.

## The hard gate (non-negotiable)

This refactor **hard-depends** on `instant-execute` being available in a **released** Nexus. On the currently pinned `2025.11.1`, converting `auto-save` to an action makes it read the pre-write snapshot, miss the just-written messages/excerpts, and decide wrong — an active regression, not an improvement. Do **not** refactor production code against the unreleased branch.

Sequencing:
1. Wait for `instant-execute` to land in a tagged Nexus release.
2. Bump `deps.edn` to that release.
3. Then execute the conversions, guarded by tests (below).

## Behavior-change watch (independent of this refactor)

Adopting any Nexus build with `instant-execute` carries one semantic shift worth noting regardless of whether we do the conversions: the read-modify-write append idioms — `excerpt/add`'s `(conj (get-in state path []) excerpt)` ([excerpt.cljs:33](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/excerpt.cljs)) and `append-pending-diffs`'s `(into existing diffs)` ([topic.cljs:63](/Users/paul/Projects/gremllm/src/gremllm/renderer/actions/topic.cljs)) — are correct today. Under instant-execute, *if* a single dispatch ever fires two same-path appends, the new model lands both where the old silently clobbered to one. No current path appears to do this; flag it during the version bump rather than treating it as a refactor target.

## Suggested direction (not prescriptive — implementation decides)

- The mechanical shape is "move registration from `register-effect!` to `register-action!`, return effects instead of nested-dispatching, and flip `:topic.effects/auto-save` → `:topic.actions/auto-save` at call sites." `auto-save` + `save-topic` are the instant-execute-gated pair; `save-active-topic` is a no-gate freebie that resolves its own TODO. Whether to land them together or incrementally is an implementation call.
- Naming: the domain word is "save," so action keywords like `:topic.actions/auto-save` read naturally. Confirm against the existing `:topic.actions/*` namespace at implementation time.
- A useful confidence oracle: a temporary read-your-own-writes test (the ADR's two-update example, or an `auto-save`-after-write case) that returns one value on `2025.11.1` and another under `instant-execute` — proves the runtime is what we think before converting anything. Treat it as a throwaway validator, not a permanent assertion about our code.

## Open questions for implementation

- Exact set to convert: `auto-save` and `save-topic` are confirmed instant-execute-gated; `save-active-topic` is convertible without the upgrade. Re-verify call-site ordering at implementation time in case new dispatch paths have appeared.
- Whether `save-active-topic`'s TODO is resolved in the same change (alongside the gated pair) or tracked as a standalone cleanup, given it doesn't share the release gate.
- The `init-session` disposition (wire up vs. delete) — independent of this work, but worth resolving while the context is fresh.
- Whether to add the read-modify-write appends to a version-bump checklist.
