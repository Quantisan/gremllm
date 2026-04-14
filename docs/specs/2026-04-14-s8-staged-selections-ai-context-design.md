# S8 Design: Staged Selections Become AI Context

**Date:** 2026-04-14
**Slice:** S8 from [docs/plans/2026-02-11-scooter-vertical-slices.md](/Users/paul/Projects/gremllm/docs/plans/2026-02-11-scooter-vertical-slices.md)
**Status:** Approved for implementation planning, revised after [docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md](/Users/paul/Projects/gremllm/docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md)

## Goal

When the user stages document selections and sends a chat message, those selections become first-class context for the turn:

- The sent user message persists which excerpts were sent.
- The ACP prompt includes the full excerpt text plus advisory document location metadata.
- The staging zone clears only after the ACP prompt succeeds.
- The chat transcript shows compact, auditable excerpt references without dumping full quote walls into the bubble.

This slice builds on S7.1-S7.4. It does not attempt exact markdown-source anchoring, cross-block highlight reconstitution, or tracked-change recovery from locator data alone.

## Product Decisions

- Staged selections are first-class domain entities in the conversation model, not prompt-only string decorations.
- The sent chat transcript should show that excerpt context was included, but only as compact references.
- Staged selections are paired references for the user instruction, not generic background context.
- Clearing behavior is MVP-simple: keep staged selections during the in-flight request, clear them only on prompt success, leave them intact on failure.
- Document location metadata is advisory only for S8. It should use the strongest rendered-block evidence now available, but it must not claim precise source anchoring.
- `DocumentExcerpt.locator` uses an explicit rendered-block start/end range abstraction even for same-block selections.
- Same-block selections may carry rendered-block offsets. Cross-block selections never carry offsets.
- `:block-text-snippet` stays in the locator because short or repeated selections need more block context than `:text` alone.
- Replacing document content clears staged selections across all topics. Content-hash or document-version invalidation is a later refinement, not part of S8.

## Domain Model

### Ephemeral browser capture remains separate

`CapturedSelection` stays as the browser-facing schema used by the selection popover flow. It remains ephemeral state under `[:excerpt :captured]` and continues to hold raw Selection API details such as DOM nodes, geometry, and browser range offsets.

This object is not the durable domain entity. It is input to one.

### Add a reusable advisory `BlockRef`

Introduce a shared locator sub-shape for rendered block identity:

```clojure
(def BlockRef
  [:map
   [:kind :keyword]
   [:index :int]
   [:start-line :int]
   [:end-line :int]
   [:block-text-snippet :string]])
```

Notes:

- `:kind` is a semantic rendered block kind such as `:heading`, `:paragraph`, `:list-item`, or `:code-block`.
- `:index` is 1-based within the rendered article flow for human-readable labels.
- `:start-line` and `:end-line` are inclusive 1-based line spans from markdown block tokenization.
- `:block-text-snippet` is a rendered-text snapshot of that block captured at selection time. For S8 it should be populated from the block's full normalized rendered text; UI may truncate it when displaying it.

### Add a durable `DocumentExcerpt` entity

Introduce a shared durable schema for user-curated document references:

```clojure
(def DocumentExcerpt
  [:map
   [:id :string]
   [:text :string]
   [:locator
    [:map
     [:document-relative-path :string]
     [:start-block BlockRef]
     [:end-block BlockRef]
     [:start-offset {:optional true} :int]
     [:end-offset {:optional true} :int]]]])
```

Notes:

- `:text` is the exact selected text that will be sent to the agent.
- `:locator` is advisory metadata derived from the rendered document context at selection time.
- `:document-relative-path` is `document.md` for Scooter, but the field remains so the entity still makes sense if more document targets exist later.
- `:start-block` and `:end-block` are always present. For same-block selections they are identical.
- `:start-offset` and `:end-offset` are offsets within rendered block text, not markdown source offsets.
- Offsets are only allowed when `:start-block` and `:end-block` refer to the same block.
- The locator remains explicitly advisory even when start and end blocks are both present.

### Reuse `DocumentExcerpt` in both staging and message context

`[:topics <topic-id> :staged-selections]` should store `DocumentExcerpt` values, not raw `CapturedSelection` or a staging wrapper around it.

User `Message` gains optional structured context:

```clojure
(def Message
  [:map
   [:id :int]
   [:type MessageType]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]
   [:context {:optional true}
    [:map
     [:excerpts [:vector DocumentExcerpt]]]]])
```

This makes excerpt context part of persisted conversation history. Clearing the staging area after send does not erase the audit trail because the sent message keeps its own excerpt copies.

## Locator Strategy

S8 uses best-effort rendered-document locator metadata. It does not solve markdown-source anchoring.

At selection time, the renderer derives locator data from rendered DOM blocks that have been synchronized with `markdown-it` block tokenization:

- `:start-block` and `:end-block` each capture block kind, 1-based block index, inclusive 1-based line span, and rendered block text snapshot.
- Same-block selections may also capture `:start-offset` and `:end-offset` within that rendered block text.
- Cross-block selections omit offsets and rely on start/end block identity plus excerpt text.

Evidence from the 2026-04-14 spike supports the following claims:

- Same-block offsets were reliable in the fixture for paragraphs, mixed-format paragraphs, list items, and fenced code blocks.
- Cross-block selection capture can identify both endpoint blocks, but it is still only coarse provenance rather than exact re-anchoring data.
- No DOM/block count mismatch warning appeared in the spike fixture.
- `markdown-it` block tokenization was sufficient for this slice. S8 does not need a switch to `nextjournal.markdown/parse`.

Block kinds exercised directly in the spike were heading, paragraph, list-item, and code-block. `blockquote` and `table` may remain structurally supported by the extractor, but S8 should treat them as best-effort until directly tested.

The locator is used for:

- compact audit labels in the chat transcript
- extra orientation for the agent inside the ACP prompt

The locator is not used for:

- source-of-truth anchoring
- tracked-change application
- highlight restoration or exact range recovery later

## Data Flow

### 1. Capture

The selection placeholder continues to capture `CapturedSelection` for the popover flow. In the same step, the renderer also derives ephemeral locator hints that already match the durable start/end-block model.

Ephemeral excerpt-related state becomes:

- `[:excerpt :captured]` -> raw `CapturedSelection`
- `[:excerpt :anchor]` -> popover anchor context
- `[:excerpt :locator-hints]` -> start/end block metadata plus optional same-block offsets

The spike-only diagnostic fields such as selection direction, boundary node names, and common ancestor should not be part of the persisted S8 design.

### 2. Stage

When the user clicks `Stage`, a pure transform converts the ephemeral capture into a durable `DocumentExcerpt`.

Staging rules:

- Same-block selection: store identical `:start-block` and `:end-block`, plus offsets when derivable.
- Cross-block selection: store distinct `:start-block` and `:end-block`, omit offsets.

The staged-selection state under the active topic stores that durable excerpt. The popover is dismissed after staging.

Because staged selections are persisted topic state, stage/unstage/clear should also:

- mark the topic unsaved
- trigger topic auto-save

This closes the known persistence gap left by S7.3 and makes excerpt context durable before send.

No `DocumentExcerpt` should persist raw Selection API node names, DOM offsets, or geometry.

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
- If the message has excerpts, build one text block that includes the user instruction plus a structured references section.
- Each reference should include the full excerpt text and advisory locator metadata derived from `:start-block`, `:end-block`, and optional same-block offsets.
- Same-block references may be rendered as one block plus offsets.
- Cross-block references should be rendered as spanning from one rendered block to another without implying exact character anchoring across the whole selection.
- `:block-text-snippet` may be included as advisory block context when it helps disambiguate short or repeated selections, but it must not be presented as if it were the user's selected excerpt.
- Append the existing `resource_link` block for `document.md` when present.

The ACP prompt should treat excerpts as paired references for the user instruction. Example intent: if the user types `reword these`, the agent should understand `these` as the referenced excerpts in the context of the overall document.

### 5. Success and failure

Staged selections remain visible while the ACP prompt is in flight.

On ACP prompt success:

- clear `[:topics <topic-id> :staged-selections]`

On ACP prompt failure:

- keep `[:topics <topic-id> :staged-selections]` unchanged

S8 does not broaden the app's general error UX beyond preserving staged context on failure.

### 6. Document replacement invalidation

When `document.actions/set-content` replaces the current document content, clear staged selections across all topics and dismiss any open selection popover.

This matches current implemented behavior and is the S8 invalidation rule. Document revision or content-hash invalidation remains a later refinement.

## Chat Transcript Rendering

The user message bubble continues to show the typed message as the primary content.

When `message.context.excerpts` is present, render a compact `References` row under the message text:

- one compact pill per excerpt
- each pill shows a truncated excerpt text snippet, capped at 40 characters
- each pill shows a short advisory locator label derived from `:start-block` and `:end-block`, such as `p3`, `li4`, or `h1 -> p2`

The full excerpt text is not rendered in the chat bubble. `:block-text-snippet` is also not rendered by default in the bubble, though it remains available for future hover/detail affordances and prompt construction.

This keeps the transcript auditable without turning the chat history into long quoted dumps.

## Error Handling

- Empty composer text still does nothing.
- No staged selections means normal text-only send behavior.
- S8 assumes document-panel selections can derive both endpoint blocks. If that invariant breaks, do not silently persist a weaker partial `DocumentExcerpt` shape.
- If a same-block selection cannot derive offsets, it may still stage with identical `:start-block` and `:end-block`, but without fabricated offsets.
- Locator display should degrade gracefully when offsets are absent. Cross-block labels should still render from `:start-block` and `:end-block`.
- Failure to send the ACP prompt must not clear staged selections.

## Testing Strategy

### Schema

- Add schema coverage for `BlockRef`
- Add schema coverage for `DocumentExcerpt`
- Add schema coverage for `Message` with optional excerpt context

### Renderer actions

- Test capture-to-stage transformation for same-block selections with identical start/end blocks and offsets
- Test capture-to-stage transformation for cross-block selections with distinct start/end blocks and no offsets
- Test stage/unstage/clear marking the topic unsaved and triggering auto-save
- Test submit building a user message with `:context {:excerpts [...]}` when staged selections exist
- Test submit leaving staged selections intact until ACP success
- Test ACP success clearing staged selections
- Test ACP failure preserving staged selections
- Test document replacement clearing staged selections across all topics and dismissing the popover

### Main prompt assembly

- Test text-only prompt behavior remains unchanged
- Test same-block prompt behavior includes excerpt text, one block label, optional offsets, and advisory block context
- Test cross-block prompt behavior includes start/end block metadata without implying exact character anchoring across blocks
- Test `resource_link` remains appended when `document.md` exists

### UI

- Test compact reference rendering for user messages with excerpt context
- Test labels such as `p3` and `h1 -> p2`
- Test transcript rendering truncates excerpt snippets and does not render full excerpt bodies or block-text snapshots in the bubble

## Non-Goals

- No exact markdown source anchoring
- No exact line-accurate or paragraph-accurate guarantee beyond advisory rendered-block metadata
- No highlight restoration or exact range recovery for sent or staged excerpts
- No tracked-change application from locator data alone
- No quick-action topic creation work from S9
- No broader send-error recovery UX redesign

## Follow-Up TODO

The next obvious limitation after S8 is turning advisory locators into resilient source anchoring. Start/end block identity plus block-text snapshots improve provenance, but they still do not solve normalization differences, repeated text over time, or document edits after capture.
