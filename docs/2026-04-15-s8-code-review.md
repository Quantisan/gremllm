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

#### 1. No coercion/validation at the `acp/prompt` IPC boundary
**File:** `src/gremllm/main/core.cljs:120`

The handler does `(js->clj message :keywordize-keys true)` and passes straight into `prompt-content-blocks`. This violates the project's explicit boundary contract (see `CLAUDE.md` — "Schema validation at every boundary (IPC, disk, HTTP, etc.)"). The pattern already exists in `schema/codec.cljs` for secrets, topics, and workspaces; it is absent here.

**Latent bug:** `:kind` arrives as the string `"paragraph"` from JSON. `block-label` in `main/actions/acp.cljs:6-17` dispatches on keywords (`:heading`, `:paragraph`, ...). Every excerpt silently falls through to the default `(name kind)` branch and yields `"paragraph2"` instead of `"p2"` in the References section. Tests don't expose this because they construct CLJS maps directly, bypassing the `js->clj` path.

**Fix:** Add `user-message-from-ipc` in `src/gremllm/schema/codec.cljs` using `(m/coerce Schema data mt/json-transformer)` — the same pattern as `topic-from-ipc`. Call it in the IPC handler before passing to `prompt-content-blocks`.

---

#### 2. Preload double-wraps `acpPrompt`
**File:** `resources/public/js/preload.js:41,61`

`acpPrompt` is produced by `createIPCBoundary`, then immediately re-wrapped as:
```js
acpPrompt: (sessionId, message) => acpPrompt(sessionId, message)
```
This wrapper is a no-op identity. All other `createIPCBoundary` exports (`acpNewSession`, `acpResumeSession`) are exposed directly. The wrapper obscures the context-bridge semantics without adding anything.

**Fix:** Remove the wrapper; expose `acpPrompt` directly like the others.

---

### Important

#### 3. Domain name drift: excerpt / staging / staged-selections
Three names for one concept:
- `excerpt` — the schema entity (`DocumentExcerpt`)
- `staging` — the action namespace (`:staging.actions/*`)
- `staged-selections` — the persisted field on the topic

Stage/unstage handlers live in `renderer/actions/topic.cljs` but dispatch under `:staging.actions/*`. The capture/build side lives in `renderer/actions/excerpt.cljs`. There is already a `TODO` at `renderer/state/excerpt.cljs:3` hinting at the same tension.

**Recommended decomplection:** Settle on `excerpt` throughout. Rename the persisted topic field to `:excerpts`. Move stage/unstage/clear handlers to `renderer.actions.excerpt` alongside the existing capture logic. Rename the action namespace to `:excerpt.actions/*`.

---

#### 4. `:document.actions/set-content` silently clears all staged excerpts across all topics
**File:** `src/gremllm/renderer/actions/document.cljs:18-21`

Marked with its own `TODO(design)`. The action's name declares it sets content; the implementation also invalidates excerpts on every topic in the workspace. Two problems:

1. Complects content-mutation with excerpt-invalidation policy under a misleading name.
2. A workspace reload (which also triggers this action) wipes excerpts even though `DocumentExcerpt` already carries snapshot text and doesn't require the document to stay valid.

**Options:** (a) Rename to `document.actions/replace-content` with an explicit docstring stating the invalidation contract, or (b) drop the clearing behavior — excerpts carry their own snapshot text and can survive reloads.

---

#### 5. `block-label` logic duplicated
**Files:** `src/gremllm/main/actions/acp.cljs:6-17` and `src/gremllm/renderer/ui/chat.cljs:6-20`

Both implement the same kind→prefix table independently. Adding a new block kind (e.g. `:figure`) requires editing two files and risks inconsistent prompt vs. UI labels.

**Fix:** Extract to `schema/block-ref-short-label` (or `schema.cljs`) as a shared pure function.

---

#### 6. Empty-messages guard treats excerpts as second-class
**File:** `src/gremllm/renderer/actions/topic.cljs:52`

```clojure
(when (or (seq messages) (seq staged-selections)) ...)
```

The guard prevents auto-saving a topic whose only content is staged excerpts. This may be the intended skateboard behavior (don't litter disk with nearly-empty topics), but it's undocumented. One sentence of comment resolves it.

---

### Minor

7. `render-excerpt` uses `pr-str` to quote user text in the ACP prompt body (`main/actions/acp.cljs:30-31`) — leaks Clojure quoting conventions into the agent prompt. Consider Markdown fences or plain indented lines.

8. `generate-message-id` returns `js/Date.now` (`schema.cljs:25-28`) — two calls in the same millisecond collide. Pre-existing issue, but `streaming-chunk-effects` now also generates IDs per chunk in the same tick.

9. `AcpSession` has a `TODO: id should be required` (`schema.cljs:206`). Cleanup candidate, especially if you rename `:staged-selections` per issue #3.

10. `locator-label` appends byte offsets (`p2 offset 4-25`) that duplicate info already present in the quoted text returned by `render-excerpt`. The label is advisory; consider omitting the offsets.

---

## Overall assessment

**Not mergeable as-is.** Fix issue #1 before merge — it is the exact trust-boundary lapse the project's own standards describe, and it conceals a live `:kind` keywordization bug that tests do not catch. Issue #2 is a one-liner to clean up in the same pass.

Issues #3 and #4 are structural concerns most likely to cause friction in Bicycle-stage work (specialized agents, managed context). Recommended: address them in a follow-up commit on this branch before merging rather than deferring to the next milestone.

The overall shape — a single `DocumentExcerpt` value flowing document panel → topic state → user message → ACP prompt → chat render — is a clean decomplection win.
