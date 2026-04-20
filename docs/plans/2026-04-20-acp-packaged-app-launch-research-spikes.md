# Plan: Research and Spikes for ACP Packaged-App Agent Launch

**Date:** 2026-04-20
**Status:** Updated post R1–R4 research
**Input spec:** [docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md)

## Context

The investigation spec enumerated six options (A–F) for fixing the packaged-app `spawn npx ENOENT` failure. We need an evidence-based decision, but only C has a local POC and only B has concrete variants documented — the rest are docs-only. This plan closes those gaps by:

1. **Web research** to surface real-world precedent and de-risk unknowns before committing engineering time.
2. **Two spikes** to validate the top candidates (A, then C as fallback) against real packaged-app behavior, not sandbox probes.

User priors (from brainstorming):

- Hard OS-process boundary: **strong preference, not a hard requirement** — willing to accept in-process if the ACP library is well-behaved and A's transport cost is material.
- Release-toolchain additions: **open, pending research** — whether adding Bun or a bundled Node runtime is worth it depends on what other Electron agent apps actually ship.
- Decision factors are **all of: library behavior (C), transport-rewrite cost (A), real-world precedent (research)** — multi-factor, not binary.

Viable set after R1–R4: **A (utilityProcess)**, **C (in-process)**. B held in reserve.
Dropped during research: D (workers share parent OS process — no fd isolation, only sync crash containment; not a flagged risk).
Eliminated: E (security regression), F (not self-contained).

## Web Research Items

Ordered by expected decision-shaping value. Each item has an explicit "what this tells us" so we can stop when the evidence converges.

### R1. Zed's own packaging of ACP agents *(highest value)*

Zed originated ACP. Their open-source repo shows exactly how a production app launches `claude-agent-acp` and peers. This is the single most informative data point.

**Questions to answer:**
- How does Zed resolve the agent binary in a packaged build — bundled, downloaded on first run, user-provided?
- Do they subprocess or embed? If subprocess, how do they locate the runtime?
- Do they ship Node/Bun, or assume system Node?

**Tells us:** baseline precedent from the protocol's authors; sanity-check on whether in-process hosting is viable at all.

### R2. Other Electron-based agent apps

Cursor, Continue, Windsurf, Cline. How do they ship their agent runtime?

**Questions:**
- Do any of them subprocess an agent that speaks a bidirectional protocol (ACP / MCP / custom)? If so, via what mechanism — `utilityProcess`, `execFile` of bundled runtime, something else?
- Any of them embed the agent library in-process?
- Anyone shipping a Bun-compiled helper?

**Tells us:** what the "obvious" answer is in this product space; whether B variants have real traction.

### R3. `utilityProcess` + bidirectional stream patterns

Electron's `utilityProcess` fixes stdin to `ignore`. Before committing to Spike A, verify the workaround pattern exists and is clean.

**Questions:**
- Is there a documented or community pattern for NDJSON over `MessagePort` with `utilityProcess`?
- Can the ACP SDK's `ReadableStream` / `WritableStream` transport accept a MessagePort adapter cleanly?
- Any known gotchas with loading a `utilityProcess` entrypoint from inside `app.asar`?

**Tells us:** whether Spike A's transport bridge is a known-solved problem or an open research question. If the latter, the spike's cost estimate goes up.

### R4. `claude-agent-acp` EMFILE / settings-watcher origin

The local POC surfaced `EMFILE` settings-watcher warnings. Identify the source before Spike C so the spike has a clean signal.

**Questions:**
- What in the published `@agentclientprotocol/claude-agent-acp` (or transitively in `@anthropic-ai/claude-agent-sdk`) starts a file watcher at import/construction time?
- Is there a documented way to disable the watcher, or an env var / option?
- Is this a known issue upstream?

**Tells us:** whether the warning is a sandbox artifact we can ignore, or a real integration concern that affects C's viability.

### R5. Bundled Node runtime + JS entrypoint via `execFile` in Electron Forge

Only if B is reopened. R1 establishes this sub-variant (Zed's managed-Node-runtime approach) as the one with real production precedent. Bun and Node SEA are no longer the priority B variants.

**Questions:**
- How to package a Node runtime binary via Electron Forge for macOS (signing, notarization, `asarUnpack` placement)?
- What does `execFile` look like targeting a bundled runtime + JS entrypoint from `process.resourcesPath`?

**Tells us:** whether the bundled-Node B variant is production-realistic as a fallback if A and C both fail.

**Research stop condition:** when R1–R4 converge on a direction, stop. R5 only runs if B becomes a contender.

## Spike 1: `utilityProcess`-Hosted ACP (Option A) *(run first)*

### Goal

Determine the real transport-rewrite cost of A, and whether `utilityProcess`'s stdin-ignore constraint is prohibitive in practice. R1 (Zed) establishes subprocess-with-embedded-runtime as the production precedent for ACP agent hosting; this spike validates that Electron's native subprocess primitive fits the shape.

### Hypothesis being tested

> A `utilityProcess` entrypoint hosting `ClaudeAcpAgent` can bridge its NDJSON streams to `MessagePort` messages with a transport adapter small enough (≤~100 lines) that A's incremental cost over C is worth paying for the OS-process isolation benefit.

### Scope

1. Create a packaged child entrypoint (JS) that constructs `ClaudeAcpAgent` and exposes its ACP transport as a MessagePort-backed stream pair.
2. `utilityProcess.fork()` it from the main process; set up MessagePort.
3. Adapt the main-process ACP client to feed `ClientSideConnection` a `ReadableStream`/`WritableStream` pair that reads/writes MessagePort messages instead of stdin/stdout bytes.
4. Verify dev mode first: real session with document.md read, pending-diff streaming, multi-turn, resume-session.
5. Verify packaged mode: install to `/Applications`, launch from Finder, run the same session end-to-end.
6. Determine empirically whether the utility-process entrypoint loads from `app.asar` or needs `asarUnpack`.
7. Expose a `CLAUDE_AGENT_ACP_EXECUTABLE`-style env override for local dev/debug (analogous to Zed's `CLAUDE_CODE_EXECUTABLE`).

### Exit criteria (must all pass)

- Full session works through MessagePort transport in packaged mode.
- Transport adapter ≤~100 lines total (main side + child side). This is the **cost bound** — if it blows past, "low transport cost" is falsified and the spike tells us to prefer C.
- Utility-process child survives parent-process signals correctly (clean shutdown, restart).
- Asar-loading question has a definitive answer (works / needs unpack / needs bundling step).
- No regression in pending-diff rendering (`schema.codec` normalization still produces correct shape).
- `acp/resume-session` works across app restarts in packaged mode.
- Adapter-internal Claude Code CLI subprocess launches correctly in packaged mode; `asarUnpack` need for the vendored CLI determined.

### What we'd learn

- The real answer to "is A a meaningful rewrite, or a clean refactor?"
- Whether Electron's officially-recommended primitive fits the ACP bridge shape.
- If A passes, we ship — no further spikes needed.

### Rollback

Spike runs on a branch; no production paths touched until a decision is made.

## Spike 2: Harden In-Process ACP (Option C) *(fallback — run only if Spike 1 fails)*

### Goal

Determine whether in-process hosting is production-viable, or whether the POC's clean result was a sandbox artifact that won't survive a real packaged-app session.

### Hypothesis being tested

> `claude-agent-acp` can run inside Electron's main process (via `ClaudeAcpAgent` + `AgentSideConnection` + paired `TransformStream`s) for a full real session without fd leaks, event-loop pauses, or environment-assumption failures.

### Scope

1. Build a minimal main-process ACP host using the already-proven POC shape: `ClientSideConnection` ↔ paired `TransformStream`s ↔ `AgentSideConnection` ↔ `ClaudeAcpAgent`.
2. Wire it behind a feature flag in [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) so dev mode can toggle between subprocess and in-process.
3. Verify dev mode first: real session with document.md read, pending-diff streaming, multi-turn, resume-session.
4. Verify packaged mode: install to `/Applications`, launch from Finder, run the same session end-to-end.
5. Verify `claude-agent-acp` PR #454 (watcher leak fix) is present in the bundled version; patch or upgrade if not.
6. Confirm adapter-internal Claude Code CLI subprocess launches correctly in packaged mode (same asar concern as Spike 1).

### Exit criteria (must all pass)

- Clean session ≥10 minutes of real document work; no crashes, no event-loop stalls.
- EMFILE warning root cause identified (per R4: per-session `SettingsManager` watchers + SDK `settingSources` watchers) and either eliminated or documented as stable with a measured ceiling.
- fd count stable across **N full sessions** (create + tear down cycle), not just N prompts within one session. Watcher lifecycle is per-session; this is the correct signal.
- No regression in pending-diff rendering (`schema.codec` normalization still produces correct shape).
- `acp/resume-session` works across app restarts in packaged mode.
- Adapter-internal Claude Code CLI subprocess launches correctly in packaged mode; `asarUnpack` need for the vendored CLI determined.

### What we'd learn

- Is C production-viable as a fallback? If yes, and A's transport adapter exceeded its cost bound, pick C.
- Failure mode shapes the B decision: library environment assumptions → B likely needed; runtime fragility → B still needed; watcher fd exhaustion → document ceiling and accept.

### Rollback

Feature flag defaults off; subprocess path remains untouched.

## What We Explicitly Defer

- **Option D (`worker_threads`)** — deferred. Workers share the parent OS process, so they do not isolate the watcher fd pressure R4 identified. Sync-crash containment is D's only remaining hedge and is not a flagged risk.
- **Option B spikes** — only attempt if A and C both fail their exit criteria. Research (R5) runs only if B becomes a live contender. If B opens, prefer the bundled-Node-runtime + JS-entrypoint variant (Zed precedent, R1). Bun and Node SEA are not priority variants.
- **Option E (re-enable `RunAsNode`)** — security-posture regression, no gain over A/C/B.
- **Option F (external tooling prerequisite)** — already ruled out by self-containment direction.

## Decision Framework (after spikes)

R1–R4 research is complete. Apply in order:

1. **Spike 1 (A) passes its exit criteria** → pick A. R1 establishes subprocess-with-embedded-runtime as the production precedent (Zed); `utilityProcess` is Electron's native equivalent. Ship.
2. **Spike 1 fails, Spike 2 (C) passes** → pick C. Accept the loss of OS-process isolation; the spike proved the library behaves in main-process hosting and fd pressure is within a documented ceiling.
3. **Both fail** → re-open B. Run R5 research on bundled-Node-runtime packaging mechanics; pick that variant (not Bun or SEA).

## Estimated Effort

- Research: complete (R1–R4 done).
- Spike 1 (A): ~1–2 days — transport adapter is the unknown; packaging questions add variance.
- Spike 2 (C): ~1 day — POC already exists; hardening and packaged verification is the work. Only runs if Spike 1 fails.

**Sequencing note:** run Spike 1 (A) first, standalone. If it passes, stop. If it fails, run Spike 2 (C).

## Critical Files

- [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) — current subprocess launch site; both spikes modify or guard this.
- [src/gremllm/main/effects/acp.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/effects/acp.cljs) — ACP connection lifecycle; bridges Clojure side to the JS bridge.
- [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js) — packaging config; Spike 1 (A) may need `asarUnpack` entries for the utility-process entrypoint and/or vendored CLI.
- [src/gremllm/main/core.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/core.cljs) — ACP init site; the "eager init at boot" behavior matters for both spikes' verification.

## Verification

After the chosen option is implemented:

- Run `npm run dev` — full session with document.md edit proposal, accept, resume.
- Run `npm run make`; install `/Applications/Gremllm.app`; launch from Finder (clean PATH); run full session.
- `fs_usage` or `lsof` check across multiple full sessions (create + tear down) to confirm no fd leak. Watcher lifecycle is per-session; per-prompt checks are insufficient.
- Uninstall Homebrew Node temporarily; re-verify packaged launch works (proves no implicit host-tooling dependency).
