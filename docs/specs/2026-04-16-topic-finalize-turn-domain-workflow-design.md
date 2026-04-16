# Topic Finalization Workflow Design

**Date:** 2026-04-16
**Status:** Proposed for review

## Goal

Make prompt-completion persistence obvious in the domain workflow.

Today, the second save after a successful ACP prompt is necessary, but it is hidden behind `:excerpt.actions/clear`. That makes the workflow hard to read in code, hard to trace in Dataspex, and easy to misunderstand later.

This design makes the save path explicit by introducing a first-class topic-domain action for turn finalization.

## Problem

The current renderer flow has two different persistence moments:

- The user message is saved immediately when submitted.
- The completed assistant turn is saved again after ACP prompt success.

That second save currently happens indirectly:

- `:acp.actions/send-prompt` success dispatches `:excerpt.actions/clear`
- `:excerpt.actions/clear` marks the topic unsaved and triggers `:topic.effects/auto-save`

This couples a topic persistence workflow to an excerpt-clearing leaf action. The result is domain-obscure control flow:

- `clear` does more than its name suggests
- prompt completion is expressed in excerpt vocabulary instead of topic vocabulary
- the reason for the second save is not visible at the action boundary

## Design Decisions

- Prompt completion should emit a topic-domain event, not an ACP-centric workflow.
- The domain event is `:topic.actions/finalize-turn`.
- `finalize-turn` always runs on successful prompt completion, even when the sent message had no excerpts.
- Leaf excerpt actions should stay literal.
- Consumed excerpts should use the verb `consume`, not `clear`.
- Workspace/document invalidation should not reuse the same verb as user-initiated excerpt edits.
- Test coverage should stay conservative and focus on the new critical domain workflow.

## Domain Vocabulary

### `:topic.actions/finalize-turn`

Meaning:

> The current conversation turn is complete enough to durably persist.

This is the explicit domain workflow action for prompt success.

Responsibilities:

- consume excerpts used by the just-completed turn
- mark the topic unsaved
- auto-save the topic

### `:excerpt.actions/consume`

Meaning:

> Remove excerpts that were used by the just-completed turn.

This action is intentionally narrow. It clears the excerpt path for the topic and does nothing else.

It does not:

- mark unsaved
- save the topic
- express prompt completion

### `:excerpt.actions/clear-active`

Meaning:

> The user explicitly cleared the current topic's composer excerpts.

This remains a user-intent action and should continue to mark unsaved and auto-save.

### `:excerpt.actions/invalidate-across-topics`

Meaning:

> Workspace/document synchronization invalidated excerpt anchors across topics.

This is a document/workspace concern, not a topic-edit concern. It should replace the current `clear-across-topics` naming.

## Proposed Action Graph

### Submit path

1. `:form.actions/submit`
2. `:messages.actions/add-to-chat`
3. `:topic.effects/auto-save`
4. `:acp.actions/send-prompt`

This preserves the existing first save for the user message.

### Streaming path

ACP streaming updates continue to append assistant chunks in memory during `:acp.events/session-update`.

These chunks are not the place to persist the completed turn. They remain in-memory until prompt success finalizes the turn.

### Success path

1. `:acp.actions/send-prompt`
2. ACP promise resolves successfully
3. `:acp.actions/prompt-succeeded`
4. `:loading.actions/set-loading? false`
5. `:topic.actions/finalize-turn`
6. `:excerpt.actions/consume`
7. `:topic.actions/mark-unsaved`
8. `:topic.effects/auto-save`

This makes the second save explicit at the topic-domain boundary.

### Failure path

1. `:acp.actions/send-prompt`
2. ACP promise rejects
3. `:acp.actions/prompt-failed`
4. `:loading.actions/set-loading? false`

Failure does not consume excerpts and does not finalize the turn.

## Refactor Shape

### ACP actions

`send-prompt` should stay transport-oriented.

Its promise handlers should dispatch:

- `[:acp.actions/prompt-succeeded topic-id]`
- `[:acp.actions/prompt-failed topic-id]`

This adapter layer is useful because the generic promise effect appends the ACP result or error onto each callback action. The ACP callback actions can absorb that transport detail and emit the cleaner domain workflow action.

### Topic actions

Add `finalize-turn` to `renderer.actions.topic`.

It should return:

```clojure
[[:excerpt.actions/consume topic-id]
 [:topic.actions/mark-unsaved topic-id]
 [:topic.effects/auto-save topic-id]]
```

This action is the main outcome of the refactor. It is the place where the full prompt-success persistence workflow becomes obvious.

### Excerpt actions

Replace the current prompt-success helper `clear` with `consume`.

`consume` should only clear the active topic excerpt path:

```clojure
[[:effects/save (topic-state/excerpts-path topic-id) []]]
```

Keep `clear-active` as the user-facing clear action with save semantics.

Rename `clear-across-topics` to `invalidate-across-topics`.

### Document/workspace actions

Update document replacement invalidation to use `invalidate-across-topics`.

This preserves current behavior while making the action name match its domain meaning.

## Testing

Be conservative. Only add or retain tests that protect the critical domain boundary.

Keep:

- one `send-prompt` success test asserting success routes to `:acp.actions/prompt-succeeded`
- one `finalize-turn` test asserting it returns:
  - `[:excerpt.actions/consume topic-id]`
  - `[:topic.actions/mark-unsaved topic-id]`
  - `[:topic.effects/auto-save topic-id]`
- the existing document action test updated for the rename to `invalidate-across-topics`

Do not add dedicated tests for:

- `excerpt.actions/consume`
- `acp.actions/prompt-failed`

Those actions are intentionally trivial, and additional tests would not materially protect the domain logic.

## Non-Goals

- Changing when the first user-message save occurs
- Persisting assistant chunks during streaming
- Expanding error UX beyond the current loading reset behavior
- Solving document revision invalidation more fully
- Refactoring unrelated ACP or excerpt behavior

## Outcome

After this refactor, the workflow should read directly in the domain:

- user submits a message
- the message is saved
- ACP runs and streams
- successful completion finalizes the turn
- finalizing the turn consumes excerpts and persists the topic

That is the core simplification: the necessary second save remains, but it is no longer hidden inside an excerpt-clearing side effect.
