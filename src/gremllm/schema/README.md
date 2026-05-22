# Shared Schema And Codec Architecture

## Ownership

This boundary owns the data contracts shared across persistence, IPC, DOM
capture, and ACP transport. It is the place to look when a shape crossing disk,
process, or JS boundaries needs validation or translation.

## Files

- `src/gremllm/schema.cljs`: canonical data models for messages, attachments,
  excerpts, topics, sessions, and workspace-level structures
- `src/gremllm/schema/codec.cljs`: boundary adapters for disk, IPC, and DOM
  selection capture
- `src/gremllm/schema/codec/acp.cljs`: ACP wire-to-CLJS coercion — session
  updates and permission request codecs
- `src/gremllm/schema/codec/acp/permission.cljs`: pure permission policy used
  by `main.effects.acp.permission`

## Operational Rules

- add or change the canonical shape in `schema.cljs`
- add boundary-specific coercion or transport transforms in `codec.cljs` (disk,
  IPC, excerpt) or `codec/acp.cljs` (ACP wire)
- keep path and permission decision logic in `codec/acp/permission.cljs` when
  it must stay pure and reusable

## Scope Assumptions

- **No code for legacy persisted data.** Disk codecs validate against the
  current schema only. A persisted topic that doesn't match fails to load —
  by design. Translation code for shapes that don't yet exist on disk is
  YAGNI; if and when real legacy data appears, write the translator then.

## Validation Locality

Validate at trust boundaries; don't re-validate downstream of them.

- **Validate here:** ACP wire coercion (`codec/acp.cljs`), disk codec
  (`codec.cljs`), IPC codec (`codec.cljs`). These are the points where
  external data becomes internal data.
- **Don't validate here:** action handlers, UI components, internal
  builders. Code that constructs canonical shapes from already-coerced
  inputs is asserting its own correctness, not guarding a boundary —
  that's what tests are for.

If you find yourself calling `m/validate` on a value your own code just
built, you're testing the builder, not protecting a boundary. Move the
check to a test or delete it.

## Important Shapes

Shapes are labeled by role; file location follows from that.

**In-memory canonical** (`schema.cljs`):
- `Message`: tagged union of chat message kinds dispatching on `:type` —
  `:user` (text plus optional excerpts and attachments), `:assistant`,
  `:reasoning`, and `:tool-call` (carries `:tool`, `:tool-call-id`,
  `:tool-call-status`, and per-tool extras)
- `DocumentExcerpt`: durable excerpt reference stored on a topic
- `AcpSession`: session id plus pending diffs
- `Topic`: in-memory representation of a topic and its session state

**Persisted (disk)** (`schema.cljs`):
- `PersistedTopic`: EDN shape written to `topics/*.edn`

**Transmitted (boundary adapter)** (`schema/codec.cljs`):
- `WorkspaceSyncData`: payload sent from main to renderer during workspace
  hydration; defined and coerced at the IPC boundary, not in the canonical
  schema

**ACP wire** (`schema/codec/acp.cljs`):
- `AcpUpdate` / `AcpSessionUpdate`: discriminated union of ACP session update
  types and their container
- `AcpPermissionRequest`: permission request shape from the ACP SDK

**Policy** (`schema/codec/acp/permission.cljs`):
- Permission decision inputs used by `main.effects.acp.permission` to resolve
  ACP permission requests without involving the JS transport bridge

## Entry Points

- `src/gremllm/schema.cljs`
- `src/gremllm/schema/codec.cljs`
- `src/gremllm/schema/codec/acp.cljs`
- `src/gremllm/schema/codec/acp/permission.cljs`

## Tests

- `test/gremllm/schema_test.cljs` — schema validation tests
- `test/gremllm/schema/codec_test.cljs` — disk, IPC, and excerpt codec tests
- `test/gremllm/schema/codec/acp_test.cljs` — ACP boundary coercion tests
- `test/gremllm/schema/codec/acp/permission_test.cljs` — ACP permission policy tests
