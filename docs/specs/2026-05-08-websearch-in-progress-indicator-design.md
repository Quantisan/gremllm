# WebSearch In-Progress Indicator

## Problem

When the agent runs a `WebSearch`, the renderer ignores both `:tool-call` and `:tool-call-update` events for that tool. Between submitting a prompt and the eventual assistant response, the user sees only the generic "Computing…" spinner — no signal that a web search is running, what it is searching for, or that the search completed.

## Goal

A minimalist in-chat indicator, styled consistently with `reasoning-message`, that:

1. Appears as soon as a WebSearch starts (`:tool-call` event)
2. Updates with the search query once it arrives (`:tool-call-update` event)
3. Persists as a completed record in the chat thread after the search finishes — mirroring the existing Read `:tool-use` message pattern

## Design

### Visual

```
in-progress:   [▼ Searching the web — CRDT vs OT real-time...]
                  CRDT vs OT real-time collaboration WebSocket modern verdict

completed:     [▶ Searched the web — CRDT vs OT real-time...]
```

A `<details>` block — open while pending, collapsed when completed. Summary shows verb + truncated query. Body shows the full query. Styled as `.tool-search-bubble` mirroring `.reasoning-bubble` (parchment-alpha background, teal left border).

### Scope

WebSearch only, matched by `:meta.claude-code.tool-name = "WebSearch"`. Other tool kinds are either rejected at the permission boundary (WebFetch) or have dedicated flows (Read/diff). This is the first cut; the schema is generic enough to extend later.

### Event lifecycle

```
:tool-call  (status: "pending", title: "Web search", no raw-input)
  → append :tool-search Message to :messages

:tool-call-update  (raw-input.query: "<query>", title: "<query>")
  → upsert into existing message: set :query, update :text

:tool-call-update  (status: "completed")
  → upsert into existing message: set :status "completed"
```

The merge semantics for upsert: if `:status` is present, set it; if `:raw-input.query` or `:title` is present, set `:query`. All three events can arrive as deltas; we accumulate.

### State shape

Messages live in `[:topics <topic-id> :messages]` as today. New optional fields on `Message`:
- `:tool-call-id` — correlates delta events to the original message
- `:status` — string ("pending", "in_progress", "completed", "failed"); display logic buckets into in-progress vs. done
- `:query` — the search query; nil until the `:tool-call-update` arrives

New `MessageType` variant: `:tool-search`.

In-place mutation uses the existing `[:effects/save path val]` primitive, found by linear scan over `:messages` by `:tool-call-id`. Same pattern as `append-to-response` in `actions/acp.cljs:11-20`.

### Spinner overlap fix

`ui.cljs:65-66` currently shows the "Computing…" spinner when `tail != :assistant`. With `:tool-search` as a possible tail, both indicators would appear simultaneously. The fix inverts the check to `tail = :user` — spinner only shows between user submission and the first response of any kind. Side benefit: clears a pre-existing stutter during `:reasoning` streaming.

## Non-goals

- **Search results body**: the `<details>` body shows the query only. ACP `content` deltas for results are not captured.
- **Other tool kinds**: separate follow-up.
- **Failure / orphan handling**: stale `:status "pending"` records on crash are acceptable for v1.
- **Permission-needed state**: WebSearch is auto-permitted server-side; the renderer never needs a "waiting for permission" UI.

## Files affected

| File | Change |
|---|---|
| `src/gremllm/schema.cljs` | Add `:tool-search` to `MessageType`; add optional fields to `Message` |
| `src/gremllm/schema/codec/acp.cljs` | Declare `:title`, `:status`, `:raw-input` on `AcpToolCall` / `AcpToolCallUpdate` |
| `src/gremllm/renderer/actions/acp.cljs` | Extend `handle-tool-event` with WebSearch branches |
| `src/gremllm/renderer/state/topic.cljs` | Add `find-message-index-by-tool-call-id` helper |
| `src/gremllm/renderer/actions.cljs` | Register `:topic.actions/upsert-tool-search` |
| `src/gremllm/renderer/ui/elements.cljs` | Add `tool-search-message` defalias |
| `src/gremllm/renderer/ui/chat.cljs` | Add `:tool-search` render case |
| `src/gremllm/renderer/ui.cljs` | Fix `awaiting-response?` predicate |
| `resources/public/index.html` | Add `.tool-search-bubble` CSS |
