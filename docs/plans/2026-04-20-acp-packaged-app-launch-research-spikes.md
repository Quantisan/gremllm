# Plan: Research and Spikes for ACP Packaged-App Agent Launch

**Date:** 2026-04-20
**Status:** Proposed
**Input spec:** [docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md)

## Context

The investigation spec enumerated six options (A–F) for fixing the packaged-app `spawn npx ENOENT` failure. We need an evidence-based decision, but only C has a local POC and only B has concrete variants documented — the rest are docs-only. This plan closes those gaps by:

1. **Web research** to surface real-world precedent and de-risk unknowns before committing engineering time.
2. **Three spikes** to validate the top candidates (C, A, D) against real packaged-app behavior, not sandbox probes.

User priors (from brainstorming):

- Hard OS-process boundary: **strong preference, not a hard requirement** — willing to accept in-process if the ACP library is well-behaved and A's transport cost is material.
- Release-toolchain additions: **open, pending research** — whether adding Bun or a bundled Node runtime is worth it depends on what other Electron agent apps actually ship.
- Decision factors are **all of: library behavior (C), transport-rewrite cost (A), real-world precedent (research)** — multi-factor, not binary.

Viable set going in: **A (utilityProcess)**, **B (packaged launcher)**, **C (in-process)**, **D (worker thread)**.
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

### R5. Bun single-file executable: macOS signing/notarization track record

Only matters if research lands us on B.

**Questions:**
- Do signed/notarized macOS apps successfully ship Bun-compiled helpers today?
- Hardened runtime compatibility?
- Artifact size implications?

**Tells us:** whether B's Bun variant is production-realistic or still has rough edges.

### R6. Node SEA maturity for ESM packages (2026)

Same scope: only matters if B becomes serious and Bun is ruled out.

**Tells us:** whether the SEA variant of B is worth even considering, or still blocked by the CJS-only limitation the spec noted.

**Research stop condition:** when R1–R4 converge on a direction, stop. R5–R6 only run if B becomes a contender.

## Spike 1: Harden In-Process ACP (Option C)

### Goal

Determine whether in-process hosting is production-viable, or whether the POC's clean result was a sandbox artifact that won't survive a real packaged-app session.

### Hypothesis being tested

> `claude-agent-acp` can run inside Electron's main process (via `ClaudeAcpAgent` + `AgentSideConnection` + paired `TransformStream`s) for a full real session without fd leaks, event-loop pauses, or environment-assumption failures.

### Scope

1. Build a minimal main-process ACP host using the already-proven POC shape: `ClientSideConnection` ↔ paired `TransformStream`s ↔ `AgentSideConnection` ↔ `ClaudeAcpAgent`.
2. Wire it behind a feature flag in [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) so dev mode can toggle between subprocess and in-process.
3. Verify dev mode first: real session with document.md read, pending-diff streaming, multi-turn, resume-session.
4. Verify packaged mode: install to `/Applications`, launch from Finder, run the same session end-to-end.
5. Identify EMFILE origin using R4's findings.

### Exit criteria (must all pass)

- Clean session ≥10 minutes of real document work; no crashes, no event-loop stalls.
- EMFILE warning root cause identified and either eliminated or documented as benign.
- No regression in pending-diff rendering (`schema.codec` normalization still produces correct shape).
- `acp/resume-session` works across app restarts in packaged mode.
- fd count stable across 10+ prompts (no leak).

### What we'd learn

- Is C the simplest viable path? (prior: yes)
- If it fails, what's the blocker: library environment assumptions, runtime fragility, or process-model mismatch? That failure mode informs whether A or B is the fallback.

### Rollback

Feature flag defaults off; subprocess path remains untouched.

## Spike 2: `utilityProcess`-Hosted ACP (Option A)

### Goal

Determine the real transport-rewrite cost of A, and whether `utilityProcess`'s stdin-ignore constraint is prohibitive in practice.

### Hypothesis being tested

> A `utilityProcess` entrypoint hosting `ClaudeAcpAgent` can bridge its NDJSON streams to `MessagePort` messages with a transport adapter small enough (≤~100 lines) that A's incremental cost over C is worth paying for the process-isolation benefit.

### Scope

1. Create a packaged child entrypoint (JS) that constructs `ClaudeAcpAgent` and exposes its ACP transport as a MessagePort-backed stream pair.
2. `utilityProcess.fork()` it from the main process; set up MessagePort.
3. Adapt the main-process ACP client to feed `ClientSideConnection` a `ReadableStream`/`WritableStream` pair that reads/writes MessagePort messages instead of stdin/stdout bytes.
4. Verify the same end-to-end behavior as Spike 1's exit criteria.
5. Decide empirically whether the utility-process entrypoint loads from `app.asar` or needs `asarUnpack`.

### Exit criteria (must all pass)

- Full session works through MessagePort transport in packaged mode.
- Transport adapter ≤ ~100 lines total (main side + child side). This is the **cost bound** — if it blows past, "low transport cost" is falsified and the spike tells us to prefer C.
- Utility-process child survives parent-process signals correctly (clean shutdown, restart).
- Asar-loading question has a definitive answer (works / needs unpack / needs bundling step).

### What we'd learn

- The real answer to "is A a meaningful rewrite, or a clean refactor?"
- Whether Electron's officially-recommended primitive fits the ACP bridge shape.
- Joint with Spike 1's result: the actual A-vs-C comparison, evidence-based.

### Rollback

Spike runs on a branch; no production paths touched until a decision is made.

## Spike 3: `worker_threads`-Hosted ACP (Option D)

### Goal

Determine whether a worker-thread host provides meaningful isolation (crash containment for ordinary JS failures, CPU isolation) at lower transport and packaging cost than Option A, while avoiding main-process coupling that Option C accepts.

### Hypothesis being tested

> A `worker_threads` Worker hosting `ClaudeAcpAgent` delivers enough isolation from the Electron main process to contain ordinary library failures, and its `MessagePort` transport is cheaper to build than `utilityProcess`'s (because Worker integrates more natively with Node streams and loads JS directly without the packaged-entrypoint questions `utilityProcess` raises).

### Scope

1. Create a Worker entrypoint that constructs `ClaudeAcpAgent` and exposes its ACP transport over the Worker's `parentPort`.
2. Main process spawns the Worker via `new Worker(...)` and adapts `ClientSideConnection` to read/write through `MessagePort`.
3. Reuse as much of Spike 2's transport-adapter code as possible — the `MessagePort` ↔ `ReadableStream`/`WritableStream` bridge is the same shape.
4. Verify the same end-to-end exit criteria as Spike 1 in packaged mode.
5. Confirm Electron's embedded Node in the main process gives the Worker a Node environment sufficient for `claude-agent-acp` (file system, child_process for Claude CLI resolution if used, timers).

### Exit criteria (must all pass)

- Full session works through Worker + MessagePort transport in packaged mode.
- Transport adapter is no larger than Spike 2's (reusing the MessagePort bridge).
- Worker survives a **deliberately-injected library failure** (e.g. throw in the agent's prompt handler) without taking down the main process — this is the isolation claim being tested.
- No regression in pending-diff streaming or `acp/resume-session`.
- Worker entrypoint loads cleanly from packaged app (likely from `app.asar`, but verify).

### What we'd learn

- Does worker-thread isolation actually contain the failure modes we'd worry about in C? (If the answer is "no, the library's failures crash the parent regardless," D collapses into C.)
- Is D's transport+packaging cost genuinely lighter than A's, or roughly equivalent?
- Joint with Spikes 1 and 2: the full spectrum of "how much isolation buys how much complexity."

### Rollback

Spike runs on a branch; no production paths touched.

## What We Explicitly Defer

- **Option B spikes** — only attempt if all of A, C, D fail their exit criteria. Research (R5, R6) runs only if B becomes a live contender.
- **Option E (re-enable `RunAsNode`)** — security-posture regression, no gain over A/B/D.
- **Option F (external tooling prerequisite)** — already ruled out by self-containment direction.

## Decision Framework (after research + spikes)

Apply in order:

1. **All three spikes pass their exit criteria** → choose on research precedent (R1, R2) plus isolation need.
   - If mature ACP-hosting apps embed in-process → pick C (simplest; precedent supports it).
   - If they subprocess via OS-level isolation → pick A.
   - If we want isolation but can't justify A's packaging cost and D contained the injected-failure test → pick D.
   - Absent clear precedent, default to **C** (fewest moving parts).
2. **C fails but A and/or D pass** → compare A vs D on cost. Prefer D if its adapter is meaningfully smaller and its isolation claim held; otherwise A.
3. **Only C passes** → pick C. Accept the process-boundary tradeoff; the spike proved the library behaves.
4. **Only A passes** → pick A. Pay the transport+packaging cost for hard isolation.
5. **Only D passes** → pick D. (Implies C's library behavior is fragile in main-thread hosting but survives in a worker, and A's packaging is prohibitive — a narrow but possible outcome.)
6. **None pass** → re-open B with R5/R6 research; pick the variant with the lightest release-toolchain impact.

## Estimated Effort

- Research: ~2–4 hours focused reading (R1–R4 is the required block).
- Spike 1 (C): ~1 day — POC already exists; hardening and packaged verification is the work.
- Spike 2 (A): ~1–2 days — transport adapter is the unknown; packaging questions add variance.
- Spike 3 (D): ~0.5–1 day — transport adapter reuses Spike 2's; the incremental work is the Worker bootstrap and the injected-failure isolation test.

**Sequencing note:** run Spike 2 before Spike 3 so the MessagePort transport adapter is already built; Spike 3 then tests the "same transport, different host" hypothesis with minimal added cost.

## Critical Files

- [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) — current subprocess launch site; all spikes modify or guard this.
- [src/gremllm/main/effects/acp.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/effects/acp.cljs) — ACP connection lifecycle; bridges Clojure side to the JS bridge.
- [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js) — packaging config; Spike 2 may need `asarUnpack` entries.
- [src/gremllm/main/core.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/core.cljs) — ACP init site; the "eager init at boot" behavior matters for all spikes' verification.

## Verification

After the chosen option is implemented:

- Run `npm run dev` — full session with document.md edit proposal, accept, resume.
- Run `npm run make`; install `/Applications/Gremllm.app`; launch from Finder (clean PATH); run full session.
- `fs_usage` or `lsof` check during a 10-prompt session to confirm no fd leak.
- Uninstall Homebrew Node temporarily; re-verify packaged launch works (proves no implicit host-tooling dependency).
