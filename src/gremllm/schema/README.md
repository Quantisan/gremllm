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

- `Message`: structured user or assistant chat item, including optional excerpt
  context and attachments
- `DocumentExcerpt`: durable excerpt reference stored on a topic
- `PersistedTopic` and `Topic`: disk shape versus in-memory shape
- `AcpSession`: session id plus pending diffs
- `WorkspaceSyncData`: payload sent from main to renderer during workspace
  hydration

## Entry Points

- `src/gremllm/schema.cljs`
- `src/gremllm/schema/codec.cljs`
- `src/gremllm/schema/codec/acp_permission.cljs`
