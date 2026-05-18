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
  `AgentSideConnection`, session cwd tracking
- `src/gremllm/main/effects/acp.cljs`: initialization, handshake, permission
  resolution, file-read callback wiring, session update dispatch, prompt and
  session APIs
- `src/gremllm/schema/codec/acp_permission.cljs`: pure CLJS permission policy
  invoked by `main.effects.acp`; keeps workspace-path checks and approval
  logic out of the JS transport bridge

## File Writes

Gremllm does not implement the ACP `writeTextFile` client method and advertises
`fs.writeTextFile: false` in its capabilities. The agent's MCP `Edit`/`Write`
tools write directly through Claude Code's internal filesystem path after
permission is granted; gating happens at the permission layer (deferred
approval via `requestPermission`), not the write layer.

## Entry Points

- `src/js/acp/index.js`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/schema/codec/acp_permission.cljs`
