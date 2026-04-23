# Shared Schema And Codec Architecture

## Ownership

This boundary owns the data contracts shared across persistence, IPC, DOM
capture, and ACP transport. It is the place to look when a shape crossing disk,
process, or JS boundaries needs validation or translation.

## Files

- `src/gremllm/schema.cljs`: canonical data models for messages, attachments,
  excerpts, topics, sessions, and workspace-level structures
- `src/gremllm/schema/codec.cljs`: boundary adapters for disk, IPC, DOM
  selection capture, ACP session updates, and system info
- `src/gremllm/schema/codec/acp_permission.cljs`: pure permission policy used
  by `main.effects.acp`

## Operational Rules

- add or change the canonical shape in `schema.cljs`
- add boundary-specific coercion or transport transforms in `codec.cljs`
- keep path and permission decision logic in `codec/acp_permission.cljs` when
  it must stay pure and reusable

## Important Shapes

Shapes are labeled by role; file location follows from that.

**In-memory canonical** (`schema.cljs`):
- `Message`: structured user or assistant chat item, including optional excerpt
  context and attachments
- `DocumentExcerpt`: durable excerpt reference stored on a topic
- `AcpSession`: session id plus pending diffs
- `Topic`: in-memory representation of a topic and its session state

**Persisted (disk)** (`schema.cljs`):
- `PersistedTopic`: EDN shape written to `topics/*.edn`

**Transmitted (boundary adapter)** (`schema/codec.cljs`):
- `WorkspaceSyncData`: payload sent from main to renderer during workspace
  hydration; defined and coerced at the IPC boundary, not in the canonical
  schema

**Policy** (`schema/codec/acp_permission.cljs`):
- Permission decision inputs used by `main.effects.acp` to resolve ACP
  permission requests without involving the JS transport bridge

## Entry Points

- `src/gremllm/schema.cljs`
- `src/gremllm/schema/codec.cljs`
- `src/gremllm/schema/codec/acp_permission.cljs`

## Tests

- `test/gremllm/schema_test.cljs` — schema validation tests
