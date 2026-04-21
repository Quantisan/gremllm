# ACP Packaged Host Closeout

**Date:** 2026-04-21
**Status:** Final branch closeout for packaged-agent launch work

## Outcome

- Gremllm hosts `claude-agent-acp` in-process through `src/js/acp/index.js`.
- Packaged launch depends on two coordinated controls:
  - `FuseV1Options.RunAsNode: true` in `forge.config.js`
  - `ELECTRON_RUN_AS_NODE=1` injected via `src/gremllm/main/effects/acp.cljs` session metadata
- `settingSources: []` is passed on session creation to suppress Claude Code SDK user/project/local settings loading and reduce watcher pressure during Gremllm sessions.
- The packaged path does not require `asarUnpack` for the pinned Claude Agent SDK CLI path.

## Production Touchpoints

- `forge.config.js`
- `src/gremllm/main/effects/acp.cljs`
- `src/js/acp/index.js`
- `src/js/acp/permission.js`

## Primary Sources

- Electron fuses docs: `https://packages.electronjs.org/fuses`
- Pinned adapter behavior in this repo: `node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137`
- Upstream adapter repo: `https://github.com/agentclientprotocol/claude-agent-acp/tree/v0.29.2`
- ACP SDK stream/connection implementation in this repo:
  - `node_modules/@agentclientprotocol/sdk/dist/acp.js`
  - `node_modules/@agentclientprotocol/sdk/dist/stream.js`
- Upstream ACP SDK repo: `https://github.com/agentclientprotocol/typescript-sdk`

## Why These Knobs Exist

`claude-agent-acp@0.29.2` reads `params._meta?.claudeCode?.options`, merges `env`, defaults `settingSources`, and then hardcodes `executable: process.execPath` for the non-static-binary path. In packaged Electron, `process.execPath` is the app binary, so `RunAsNode` must remain enabled and the child env must include `ELECTRON_RUN_AS_NODE=1` or the binary relaunches the app instead of acting as a Node interpreter. `settingSources: []` remains because the same adapter path defaults to `["user", "project", "local"]`, which adds settings-file loading that Gremllm does not need.

## Trade-Offs To Re-Evaluate Before Future Changes

- `RunAsNode` makes `ELECTRON_RUN_AS_NODE` meaningful again for the packaged app binary.
- The in-process bridge depends on ACP SDK generic-stream support and Gremllm's paired `TransformStream` bridge rather than subprocess stdio.
- The adapter behavior described above is pinned to `@agentclientprotocol/claude-agent-acp@0.29.2`; re-check the sources on dependency upgrades.
