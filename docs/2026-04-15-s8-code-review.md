# Code Review: feat/s8-staged-selections-context

**Date:** 2026-04-15
**Branch:** `feat/s8-staged-selections-context`
**Commit range:** `6550992..ab17e5e` (15 commits)
**Reviewer:** Claude Sonnet 4.6 (dispatched via superpowers:code-reviewer)
**Focus:** Tech design, architecture, and implementation simplicity (Rich Hickey)

## What was implemented

**S8: Staged Selections Become AI Context.** Users highlight text in the document panel, stage those selections as "excerpts", and when they submit a chat message the excerpts are attached as `:context`. They travel to the ACP agent as a structured References section. A compact References row renders on the user's message bubble.

New domain shapes introduced (see `src/gremllm/schema.cljs:146-179`):
- `BlockRef` — identifies a markdown block by start/end offsets in source
- `DocumentExcerpt` — `{ :id, :text, :locator { :start BlockRef, :end BlockRef } }`
- `Message :context` — optional vector of `DocumentExcerpt`s on a user message
- Structured user message crosses the `acp/prompt` IPC boundary as `{ :text, :context }`

Related docs:
- Spec: `docs/specs/2026-04-14-s8-staged-selections-ai-context-design.md`
- Plan: `docs/plans/2026-04-14-Staged-Selections-Become-AI-Context.md`
- Locator spike findings: `docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md`

---

## Strengths

- Schema-first domain — `BlockRef`, `DocumentExcerpt`, and `Message :context` are plain maps described by malli. No incidental wrapper types.
- `StagedSelection` → `DocumentExcerpt` cleanly decomplects ephemeral capture from durable reference (different state paths, different lifetimes: `[:excerpt :captured]` vs. persisted topic field).
- `prompt-content-blocks` (`main/actions/acp.cljs:39-52`) is a pure, inspectable transform from `{:text :context}` → ACP content blocks; directly exercised in tests without mocking.
- `selection-locator` (`renderer/ui/document/locator.cljs:88-107`) is pure; the imperative DOM reader `selection-locator-from-dom` is separate.
- `submit-messages` (`renderer/actions/ui.cljs:23-37`) threads one message value to both `add-to-chat` and `send-prompt` — no divergence between in-memory and on-wire shapes.

---

## Issues

### Critical

#### [FIXED] 1. No coercion/validation at the `acp/prompt` IPC boundary
**File:** `src/gremllm/main/core.cljs:120`

The handler does `(js->clj message :keywordize-keys true)` and passes straight into `prompt-content-blocks`. This violates the project's explicit boundary contract (see `CLAUDE.md` — "Schema validation at every boundary (IPC, disk, HTTP, etc.)"). The pattern already exists in `schema/codec.cljs` for secrets, topics, and workspaces; it is absent here.

**Latent bug:** `:kind` arrives as the string `"paragraph"` from JSON. `block-label` in `main/actions/acp.cljs:6-17` dispatches on keywords (`:heading`, `:paragraph`, ...). Every excerpt silently falls through to the default `(name kind)` branch and yields `"paragraph2"` instead of `"p2"` in the References section. Tests don't expose this because they construct CLJS maps directly, bypassing the `js->clj` path.

**Fix:** Add `user-message-from-ipc` in `src/gremllm/schema/codec.cljs` using `(m/coerce Schema data mt/json-transformer)` — the same pattern as `topic-from-ipc`. Call it in the IPC handler before passing to `prompt-content-blocks`.

---

#### [FIXED] 2. Staging cleanup races on topic switch during in-flight prompt
**Files:** `src/gremllm/renderer/actions/acp.cljs:99-112`, `src/gremllm/renderer/actions/topic.cljs:77-82`

`send-prompt` captures the originating `topic-id` from the active topic at send time, but `[:staging.actions/clear-staged]` in the `on-success` vector does not receive that id. `clear-staged` (`topic.cljs:77`) re-reads `(topic-state/get-active-topic-id state)` at resolve time, so it clears whichever topic is active when the promise resolves — not the originating one. `mark-active-unsaved` in that chain (`topic.cljs:41-43`) has the same problem.

**Behavior:** if the user switches topics while ACP is in flight, the newly-active topic's staged selections are wiped and auto-saved, while the originating topic retains its stale staged selections.

**Fix:** make `clear-staged` accept an explicit `topic-id` argument; dispatch it from `send-prompt` with the captured `topic-id`. Replace `[:staging.actions/clear-staged]` with `[:staging.actions/clear-staged topic-id]` and replace `mark-active-unsaved` with `mark-unsaved topic-id` in the success chain. Add a test that flips `:active-topic-id` before the promise resolves.

---

#### [FIXED] 3. Preload double-wraps `acpPrompt`
**File:** `resources/public/js/preload.js:41,61`

`acpPrompt` is produced by `createIPCBoundary`, then immediately re-wrapped as:
```js
acpPrompt: (sessionId, message) => acpPrompt(sessionId, message)
```
This wrapper is a no-op identity. All other `createIPCBoundary` exports (`acpNewSession`, `acpResumeSession`) are exposed directly. The wrapper obscures the context-bridge semantics without adding anything.

**Fix:** Remove the wrapper; expose `acpPrompt` directly like the others.

---

### Important

#### [FIXED] 4. Domain name drift: excerpt / staging / staged-selections
Three names for one concept:
- `excerpt` — the schema entity (`DocumentExcerpt`)
- `staging` — the action namespace (`:staging.actions/*`)
- `staged-selections` — the persisted field on the topic

Stage/unstage handlers live in `renderer/actions/topic.cljs` but dispatch under `:staging.actions/*`. The capture/build side lives in `renderer/actions/excerpt.cljs`. There is already a `TODO` at `renderer/state/excerpt.cljs:3` hinting at the same tension.

**Recommended decomplection:** Settle on `excerpt` throughout. Rename the persisted topic field to `:excerpts`. Move stage/unstage/clear handlers to `renderer.actions.excerpt` alongside the existing capture logic. Rename the action namespace to `:excerpt.actions/*`.

---

#### 5. `:document.actions/set-content` silently clears all staged excerpts across all topics
**File:** `src/gremllm/renderer/actions/document.cljs:18-21`

Marked with its own `TODO(design)`. The action's name declares it sets content; the implementation also invalidates excerpts on every topic in the workspace. Two problems:

1. Complects content-mutation with excerpt-invalidation policy under a misleading name.
2. A workspace reload (which also triggers this action) wipes excerpts even though `DocumentExcerpt` already carries snapshot text and doesn't require the document to stay valid.

**Options:** (a) Rename to `document.actions/replace-content` with an explicit docstring stating the invalidation contract, or (b) drop the clearing behavior — excerpts carry their own snapshot text and can survive reloads.

**Note on option (a):** `clear-staged-across-topics` (`topic.cljs:84-87`) only emits `[:effects/save … []]` per topic — no `mark-unsaved`, no `auto-save`. The clear is in-memory-only. A workspace reload re-hydrates topics from disk, resurrecting excerpts that were supposedly invalidated. Option (a) is incomplete unless each cleared topic is also marked unsaved and auto-saved. This is an additional argument for option (b).

---

#### 6. `block-label` logic duplicated
**Files:** `src/gremllm/main/actions/acp.cljs:6-17` and `src/gremllm/renderer/ui/chat.cljs:6-20`

Both implement the same kind→prefix table independently. Adding a new block kind (e.g. `:figure`) requires editing two files and risks inconsistent prompt vs. UI labels.

**Fix:** Extract to `schema/block-ref-short-label` (or `schema.cljs`) as a shared pure function.

---

#### 7. Empty-messages guard treats excerpts as second-class
**File:** `src/gremllm/renderer/actions/topic.cljs:52`

```clojure
(when (or (seq messages) (seq staged-selections)) ...)
```

The guard prevents auto-saving a topic whose only content is staged excerpts. This may be the intended skateboard behavior (don't litter disk with nearly-empty topics), but it's undocumented. One sentence of comment resolves it.

---

#### [FIXED] 8. Locator failure is not fail-closed
**Files:** `src/gremllm/renderer/actions.cljs:86`, `src/gremllm/renderer/ui/document/locator.cljs:121-139`, `src/gremllm/renderer/actions/excerpt.cljs:24-30`, `src/gremllm/schema.cljs:163-169`

`selection-locator-from-dom` returns `nil` when either selection endpoint lacks a block-selector ancestor. The call site (`renderer/actions.cljs:86`) passes that `nil` as `locator-hints`. `excerpt/stage` then hands it directly to `capture->excerpt`, which sets `:locator nil` on the resulting `DocumentExcerpt`.

`DocumentExcerpt.locator` is a **required** map in the schema (`schema.cljs:163-169`). The staging action succeeds; the schema violation surfaces later when the next auto-save runs `topic-to-ipc` → `m/coerce schema/Topic`, which throws. Net effect: the user stages an excerpt successfully, then the background save blows up silently.

**Fix:** refuse to stage when `locator-hints` is nil — return `[[:excerpt.actions/dismiss-popover]]` (optionally with a warning in state). This matches the "auditable first-class context" intent better than staging an invalid excerpt. Alternatively, extend the schema to allow a `:no-locator` shape and propagate that through the pipeline.

---

#### 9. Same-block offsets use first-text-match, not the actual selection position
**File:** `src/gremllm/renderer/ui/document/locator.cljs:99-107`

When start and end blocks are the same, `selection-locator` computes offsets via `(.indexOf block-text selected-text)`. This returns the **first** occurrence of the selected text in the block, regardless of which occurrence the user highlighted.

For blocks with repeated substrings (common in lists and tables), the stored `:start-offset` / `:end-offset` is wrong, and the ACP prompt's `offset 4-25` label actively misleads the agent.

**Fix options:** (a) drop offsets entirely — the quoted text in `render-excerpt` already identifies the content, making offsets redundant and potentially wrong; (b) derive block-relative offsets from the DOM Range in `selection-locator-from-dom` rather than via post-hoc string search.

---

### Minor

9. `render-excerpt` uses `pr-str` to quote user text in the ACP prompt body (`main/actions/acp.cljs:30-31`) — leaks Clojure quoting conventions into the agent prompt. Consider Markdown fences or plain indented lines.

10. `generate-message-id` returns `js/Date.now` (`schema.cljs:25-28`) — two calls in the same millisecond collide. Pre-existing issue, but `streaming-chunk-effects` now also generates IDs per chunk in the same tick.

11. `AcpSession` has a `TODO: id should be required` (`schema.cljs:206`). Cleanup candidate, especially if you rename `:staged-selections` per issue #4.

---

## Overall assessment

**Not mergeable as-is.** There are three critical issues:

1. **IPC boundary lacks coercion** (issue #1) — violates the project's explicit trust-boundary contract and conceals a live `:kind` keywordization bug that tests do not catch. Issue #3 (preload double-wrap) is a one-liner to clean up in the same pass.
2. **Staging cleanup races on topic switch** (issue #2) — `clear-staged` uses the active topic at resolve time, not the originating topic at send time. A realistic user action (switching topics during a slow ACP call) silently corrupts the wrong topic's staged selections.
3. **Nil locator stages invalid durable state** (issue #8) — a selection outside a block-selector element produces a nil locator, stages successfully, then throws at the next auto-save.

Issues #4, #5, and #9 are structural concerns most likely to cause friction in Bicycle-stage work (specialized agents, managed context). Recommended: address them in a follow-up commit on this branch before merging rather than deferring to the next milestone.

The overall shape — a single `DocumentExcerpt` value flowing document panel → topic state → user message → ACP prompt → chat render — is a clean decomplection win.
