# Renderer Architecture

## Ownership

The renderer owns the Nexus store, Replicant rendering, preload-driven IPC
consumption, topic and document state, and the document-first user workflows
that tie chat activity back to `document.md`.

## Structure

- `core.cljs`: renderer bootstrap, IPC listeners, render loop, initial dispatch
- `actions.cljs`: action and effect registration, DOM placeholders, promise
  handling, store-level effects
- `actions/`: domain actions for UI, workspace, topics, messages, ACP,
  excerpts, and document state
- `state/`: focused selectors and paths for document, workspace, topic, form,
  excerpt, loading, and UI state
- `ui.cljs`: app-level composition
- `ui/`: chat, topics, welcome, markdown, and document views
- `ui/document/`: source-aware helpers for diff composition, source locator
  metadata, and excerpt highlighting

## Hot Paths

### App Bootstrap

Start in `core.cljs`, which wires preload listeners for `workspace:opened`,
`acp:session-update`, and `menu:command`, installs the render watcher, and
dispatches the initial workspace bootstrap action.

### Workspace Hydration

Follow `renderer.actions.workspace/opened`, which normalizes the
`workspace:opened` payload into renderer state including workspace metadata,
topic map, and document content.

### Prompt Submission

Dispatched as `:form.actions/submit` (registered in `renderer/actions.cljs`),
which calls `renderer.actions.ui/submit-messages`. That function builds the
structured user message, dispatches chat updates, and calls
`renderer.actions.acp/send-prompt`.

### Streaming Session Updates

Start in `renderer.actions.acp/session-update`, which routes assistant chunks,
reasoning chunks, tool events, and pending diff accumulation into topic state.
The `AcpUpdate` coercion happens at the IPC boundary in
`src/gremllm/schema/codec.cljs` before the update reaches this action.

### Excerpt Capture And Document Review

Start in `renderer.ui.document`, `renderer.actions.excerpt`, and
`renderer.ui.document.locator`. Selection capture originates from the document
panel, durable excerpt data is stored on the active topic, and highlights are
re-synced after markdown re-render.

### Menu Commands

`onMenuCommand` listener in `renderer/core.cljs` routes `:save-topic` keywords
from the main-process menu into Nexus dispatches. This lives in `core.cljs`
(not `actions/`) because it is a preload-event subscription wired at bootstrap,
not a domain action.

## Entry Points

- `src/gremllm/renderer/core.cljs`
- `src/gremllm/renderer/actions.cljs`
- `src/gremllm/renderer/actions/ui.cljs`
- `src/gremllm/renderer/actions/acp.cljs`
- `src/gremllm/renderer/actions/excerpt.cljs`
- `src/gremllm/renderer/ui/document.cljs`
- `src/gremllm/renderer/ui/document/diffs.cljs`
- `src/gremllm/renderer/ui/document/locator.cljs`
- `src/gremllm/renderer/ui/document/highlights.cljs`

## Tests

- `test/gremllm/renderer/actions/` — renderer action tests (excerpt, message, etc.)
