# S8 Design: Staged Selections Become AI Context

**Date:** 2026-04-13
**Slice:** S8 from [docs/plans/2026-02-11-scooter-vertical-slices.md](/Users/paul/Projects/gremllm/docs/plans/2026-02-11-scooter-vertical-slices.md)
**Status:** Approved for implementation planning

## Goal

When the user stages document selections and sends a chat message, those selections become first-class context for the turn:

- The sent user message persists which excerpts were sent.
- The ACP prompt includes the full excerpt text plus advisory document location metadata.
- The staging zone clears only after the ACP prompt succeeds.
- The chat transcript shows compact, auditable excerpt references without dumping full quote walls into the bubble.

This slice builds on S7.1-S7.4. It does not attempt true source anchoring or highlight reconstitution.

## Product Decisions

- Staged selections are first-class domain entities in the conversation model, not prompt-only string decorations.
- The sent chat transcript should show that excerpt context was included, but only as compact snippets.
- Staged selections are paired references for the user instruction, not generic background context.
- Clearing behavior is MVP-simple: keep staged selections during the in-flight request, clear them only on prompt success, leave them intact on failure.
- Document location metadata is advisory only for S8. It should help the agent orient itself, but it must not claim precise source anchoring.

## Domain Model

### Ephemeral browser capture remains separate

`CapturedSelection` stays as the browser-facing schema used by the selection popover flow. It remains ephemeral state under `[:excerpt :captured]` and continues to hold raw Selection API details such as DOM nodes, geometry, and range offsets.

This object is not the durable domain entity. It is an input to one.

### Add a durable `DocumentExcerpt` entity

Introduce a shared durable schema for user-curated document references:

```clojure
{:id :string
 :text :string
 :locator {:document-relative-path :string
           :block-kind {:optional true} :keyword
           :block-index {:optional true} :int
           :block-text-snippet {:optional true} :string
           :start-offset {:optional true} :int
           :end-offset {:optional true} :int}}
```

Notes:

- `:text` is the full selected text that will be sent to the agent.
- `:locator` is advisory metadata derived from the rendered document context at selection time.
- `:block-index` is 1-based for easier human interpretation in UI and prompts.
- `:document-relative-path` is `document.md` for Scooter, but the field is included so the entity still makes sense if more document targets exist later.
- Locator fields other than `:document-relative-path` are optional. The excerpt should still be valid when only partial advisory metadata is available.
- `:start-offset` and `:end-offset` are offsets within the rendered block text when derivable. They are not markdown source offsets.

### Reuse `DocumentExcerpt` in both staging and message context

`[:topics <topic-id> :staged-selections]` should store `DocumentExcerpt` values, not raw `CapturedSelection`.

User `Message` gains optional structured context:

```clojure
{:id :int
 :type :user
 :text :string
 :context {:excerpts [DocumentExcerpt ...]}}
```

This makes excerpt context part of persisted conversation history. Clearing the staging area after send does not erase the audit trail because the sent message keeps its own excerpt copies.

## Locator Strategy

S8 uses best-effort rendered-document locator hints. It does not solve markdown-source anchoring.

At selection time, the renderer derives locator hints from the rendered document DOM:

- nearest semantic block element in the document article
- block kind such as `:paragraph`, `:heading`, `:list-item`, `:blockquote`, or `:code-block`
- 1-based block index within the rendered article flow
- short snippet of the enclosing block text
- start/end offsets within that block text when derivable

The locator is used for:

- compact audit labels in the chat transcript
- extra orientation for the agent inside the ACP prompt

The locator is not used for:

- source-of-truth anchoring
- tracked-change application
- re-highlighting selections later

## Data Flow

### 1. Capture

The selection placeholder continues to capture `CapturedSelection` for the popover flow. In the same step, the renderer also derives ephemeral locator hints from the rendered document context.

Ephemeral excerpt-related state becomes:

- `[:excerpt :captured]` -> raw `CapturedSelection`
- `[:excerpt :anchor]` -> popover anchor context
- `[:excerpt :locator-hints]` -> rendered-block metadata used to build a durable excerpt

### 2. Stage

When the user clicks `Stage`, a pure transform converts the ephemeral capture into a durable `DocumentExcerpt`.

The staged-selection state under the active topic stores that durable excerpt. The popover is dismissed after staging.

Because staged selections are persisted topic state, stage/unstage/clear should also:

- mark the topic unsaved
- trigger topic auto-save

This closes the known persistence gap left by S7.3 and makes excerpt context durable before send.

### 3. Submit

`form.actions/submit` builds a user message with:

- primary text from the composer textarea
- optional `:context {:excerpts [...]}` copied from the current staged selections

That structured user message is appended to chat and persisted as part of topic history.

The ACP send path should receive the full user message, not just raw text.

### 4. Prompt build

Main-side prompt assembly stays the single owner of ACP prompt formatting.

Replace the text-only prompt builder with a pure function that accepts:

- the structured user message
- optional document path

Prompt behavior:

- If the message has no excerpts, preserve current behavior.
- If the message has excerpts, build one text block that includes:
  - the user instruction
  - a structured references section listing each excerpt’s full text
  - advisory locator metadata for each excerpt
- Append the existing `resource_link` block for `document.md` when present.

The ACP prompt should treat excerpts as paired references for the user instruction. Example intent: if the user types “reword these”, the agent should understand “these” as the referenced excerpts in the context of the overall document.

### 5. Success and failure

Staged selections remain visible while the ACP prompt is in flight.

On ACP prompt success:

- clear `[:topics <topic-id> :staged-selections]`

On ACP prompt failure:

- keep `[:topics <topic-id> :staged-selections]` unchanged

S8 does not broaden the app’s general error UX beyond preserving staged context on failure.

## Chat Transcript Rendering

The user message bubble continues to show the typed message as the primary content.

When `message.context.excerpts` is present, render a compact `References` row under the message text:

- one compact pill per excerpt
- each pill shows a truncated text snippet, capped at 40 characters
- each pill also shows a short advisory locator label such as `p3`, `h2`, `li4`, or `block 7`

The full excerpt text is not rendered in the chat bubble. Full text remains available in message context for prompt construction and persistence.

This keeps the transcript auditable without turning the chat history into long quoted dumps.

## Error Handling

- Empty composer text still does nothing.
- No staged selections means normal text-only send behavior.
- If locator hints are incomplete, still build and stage the excerpt using the captured text plus whatever advisory fields are available.
- Locator display should degrade gracefully. Missing block kind or block index should fall back to a generic `block` label.
- Failure to send the ACP prompt must not clear staged selections.

## Testing Strategy

### Schema

- Add schema coverage for `DocumentExcerpt`
- Add schema coverage for excerpt locator metadata
- Add schema coverage for `Message` with optional excerpt context

### Renderer actions

- Test capture-to-stage transformation into a durable `DocumentExcerpt`
- Test stage/unstage/clear marking the topic unsaved and triggering auto-save
- Test submit building a user message with `:context {:excerpts [...]}` when staged selections exist
- Test submit leaving staged selections intact until ACP success
- Test ACP success clearing staged selections
- Test ACP failure preserving staged selections

### Main prompt assembly

- Test text-only prompt behavior remains unchanged
- Test structured-message prompt behavior includes excerpt text and advisory locator metadata
- Test `resource_link` remains appended when `document.md` exists

### UI

- Test compact reference rendering for user messages with excerpt context
- Test transcript rendering truncates excerpt snippets and does not render full quote bodies in the bubble

## Non-Goals

- No true markdown source anchoring
- No line-accurate or paragraph-accurate guarantee
- No highlight restoration for sent or staged excerpts
- No quick-action topic creation work from S9
- No broader send-error recovery UX redesign

## Follow-Up TODO

The next obvious limitation after S8 is locator trust. `DocumentExcerpt.locator` should remain explicitly marked as advisory until the app has a real source-anchoring strategy that can survive markdown rendering differences, inline formatting, and document edits over time.
