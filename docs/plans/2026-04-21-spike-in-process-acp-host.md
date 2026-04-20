# Plan: Spike 2 — In-Process ACP Host (Option C)

**Date:** 2026-04-21
**Scope:** single spike, isolated in a new worktree, reversible by abandonment.
**Supersedes (for this spike only):** A-first sequencing in `docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md`.

## Context

Packaged Electron launch fails with `spawn npx ENOENT` because the app runs with no npx/node on PATH. R1–R4 narrowed the viable set to A (`utilityProcess`) and C (in-process). Verifying the pinned adapter source — `node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1133` — exposes the decisive fact: it hardcodes `executable: process.execPath` as the interpreter for Claude Code CLI. With fuse `RunAsNode: false` (`forge.config.js:41`), the Electron binary refuses to run as Node. A `utilityProcess` child inherits the same `process.execPath`, so **A does not solve runtime resolution** — it only moves where the failure surfaces.

That promotes Claude-runtime resolution to the primary gate, demotes A-vs-C host-model framing, and makes C the simpler substrate for probing the real blocker. If C clears runtime resolution and the library behaves in main, we ship C. If C fails on *library stability*, A is the justified fallback. If C fails on *runtime resolution*, the fallback is B (explicit runtime control), not A.

## Goal

Replace the `npx`-spawn path with an in-process ACP host: `ClientSideConnection` ↔ paired `TransformStream`s ↔ `AgentSideConnection` ↔ `ClaudeAcpAgent`, all in the Electron main process. Prove Claude-runtime resolution works in packaged mode or document precisely how it fails.

## Approach

### 1. Isolate the spike

- New worktree (parallel to `feat/packaged-agent-launch`), new branch `spike/acp-in-process`.
- Replace the subprocess path outright — **no feature flag**. Rollback is "abandon the branch."

### 2. Rewrite `src/js/acp/index.js`

- Remove `spawn`, `buildNpxAgentPackageConfig`, `logConfiguredAgentVersion`, subprocess stdin/stdout wiring.
- Import `ClaudeAcpAgent` from `@agentclientprotocol/claude-agent-acp` and `AgentSideConnection` from `@agentclientprotocol/sdk`.
- Build paired NDJSON `TransformStream`s (one for each direction). Feed one pair end to `new acp.ClientSideConnection(() => client, stream)` and the other to `new acp.AgentSideConnection(...)` hosting `new ClaudeAcpAgent(...)`.
- Return `{ connection, disposeAgent, protocolVersion }`. `disposeAgent` closes streams and calls any explicit teardown the library exposes.
- **Reuse unchanged:** `src/js/acp/permission.js`, `rememberToolName`, `enrichPermissionParams`, `sessionCwdMap`, the `newSession`/`unstable_resumeSession` interception that records cwd.

### 3. Runtime-resolution probe — run FIRST

Before any other correctness work, run the packaged app and capture what `claude-agent-acp` actually spawns. Known override levers (from adapter source):

- `process.env.CLAUDE_CODE_EXECUTABLE` → sets `pathToClaudeCodeExecutable` (`acp-agent.js:1134`).
- `process.env.CLAUDE_AGENT_ACP_IS_SINGLE_FILE_BUN` → `isStaticBinary()` path, uses `@anthropic-ai/claude-agent-sdk/embed` (line 43). Requires a single-file Bun build — Option B territory; if this is what's needed, spike fails, pivot to B.
- `_meta.claudeCode.options.executable` via session params — SDK's public override path (the adapter spreads `...userProvidedOptions` after its own `executable:` default).

Try in order: `CLAUDE_CODE_EXECUTABLE` pointing at a resolved, interpretable Claude CLI entry; if that requires a Node interpreter the packaged app doesn't have, spike fails → B.

### 4. Update `src/gremllm/main/effects/acp.cljs`

- State slot `:subprocess` → `:dispose-agent` (a 0-arity fn returned by the JS module).
- `shutdown` calls `dispose-agent` instead of `.kill(subprocess "SIGTERM")`. Same handling in both the `:state` and `:initialize-in-flight` branches.
- `start-connection!`'s `.catch` calls `dispose-agent` on failure.
- Delete `agent-package-mode` and its `:is-packaged?` argument — dead after the rewrite.

### 5. Opportunistically reduce settings-watcher pressure

Pass `settingSources: []` through session `_meta.claudeCode.options` (in `main.actions.acp`'s prompt construction) to disable SDK-side filesystem settings watchers. The adapter's own per-session `SettingsManager` (`dist/settings.js`) is separate and already disposed on teardown — acceptable.

### 6. Forge packaging

- `forge.config.js` starts untouched.
- Only add `asarUnpack` entries if step 3 empirically shows the Claude CLI asset must live outside asar to be interpretable. Measure, don't pre-optimize.

## Files

- `src/js/acp/index.js` — rewrite (subprocess → in-process).
- `src/gremllm/main/effects/acp.cljs` — lifecycle + teardown adaptation.
- `src/gremllm/main/actions/acp.cljs` — add `settingSources: []` to prompt `_meta`.
- `forge.config.js` — conditional `asarUnpack`, only if measurement demands.
- `src/js/acp/permission.js` — reused unchanged.

## Verification (exit criteria, in order)

1. **Dev mode:** `npm run dev` — create topic, send prompt, stream reply, `readTextFile` on `document.md`, pending-diff via dry-run `writeTextFile`, multi-turn, resume-session. All green.
2. **Runtime resolution documented:** precise override that launches Claude Code CLI in packaged mode (or the exact failure signature if none works).
3. **Packaged launch:** `npm run make`; install `/Applications/Gremllm.app`; launch from Finder in a clean shell; repeat the dev-mode session end-to-end.
4. **Session fd stability:** create + tear down ≥5 full sessions in the packaged app; `lsof -p <pid>` delta bounded (no monotonic growth). Watcher lifecycle is per-session, so this is the correct signal — per-prompt checks are insufficient.
5. **Resume across restart:** close the packaged app, relaunch, `acp/resume-session` reattaches.
6. **Pending-diff regression check:** `schema.codec` normalization + `renderer.ui.document.diffs` still render pending diffs correctly.
7. **Host-tooling independence:** hide Homebrew Node (`mv /opt/homebrew/bin/node /opt/homebrew/bin/node.hidden`); relaunch packaged app; session still works. Restore after.

## Decision gate (post-spike)

- **All criteria pass →** ship C. Delete `agent-package-mode`, npx-vendor code, this spike's debug overrides. Close A.
- **Fails at step 2 (runtime resolution) →** pivot to B (bundled Node runtime or static binary). A is not the fallback.
- **Fails at step 4 (library stability: fd leak beyond PR #454, event-loop pauses) →** A becomes justified. Open Spike 1 carrying the runtime-resolution finding from step 2 forward.

## Explicitly out of scope

- OS-process isolation (requires A).
- Bundling a Node runtime or static Claude binary (B territory).
- Renderer, preload, or IPC surface changes — in-process hosting is invisible past `src/js/acp`.
- Feature flag / subprocess fallback toggle.
