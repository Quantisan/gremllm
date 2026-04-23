# ACP Host Architecture

## Ownership

`src/js/acp/index.js` owns the in-process transport bridge between Gremllm and
`claude-agent-acp`. It is intentionally small: CLJS owns lifecycle and policy,
while this JS module owns stream wiring and connection-local transport state.

## Why This Exists

Gremllm hosts ACP in-process instead of spawning a separate subprocess
transport. The bridge uses paired `TransformStream` instances to connect the ACP
SDK client and the in-process agent over ndjson streams.

## Division Of Responsibility

- `src/js/acp/index.js`: stream bridge, `ClientSideConnection`,
  `AgentSideConnection`, session cwd tracking, dry-run `writeTextFile`
- `src/gremllm/main/effects/acp.cljs`: initialization, handshake, permission
  resolution, file-read callback wiring, session update dispatch, prompt and
  session APIs

## Dry-Run Write Behavior

`writeTextFile` intentionally acknowledges writes without mutating disk. That
keeps the agent on the proposal path so it can return a reviewable diff, while
Gremllm remains in control of whether changes are later accepted or rejected
through its own workflow.

## Entry Points

- `src/js/acp/index.js`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/schema/codec/acp_permission.cljs`
