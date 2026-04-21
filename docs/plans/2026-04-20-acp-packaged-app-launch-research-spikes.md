# Plan: Research and Spikes for ACP Packaged-App Agent Launch

**Date:** 2026-04-20
**Status:** Updated post R1–R4 research and primary-source review of `claude-agent-acp@0.29.2`
**Input spec:** [docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md)

## Context

The investigation spec enumerated six options (A–F) for fixing the packaged-app `spawn npx ENOENT` failure. We need an evidence-based decision, but only C has a local POC and only B has concrete variants documented — the rest are docs-only. This plan closes those gaps by:

1. **Web research** to surface real-world precedent and de-risk unknowns before committing engineering time.
2. **Two spikes** to validate the top candidates against real packaged-app behavior, not sandbox probes.

User priors (from brainstorming):

- Hard OS-process boundary: **strong preference, not a hard requirement** — willing to accept in-process if the ACP library is well-behaved.
- Release-toolchain additions: **open, pending research** — whether adding Bun or a bundled Node runtime is worth it depends on what other Electron agent apps actually ship.
- Decision factors are **all of: library behavior (C), runtime-resolution story, real-world precedent (research)** — multi-factor, not binary.

### Reframing: runtime resolution is the first gate, not host model

Primary-source review of the pinned adapter revealed that `claude-agent-acp@0.29.2` hardcodes `executable: isStaticBinary() ? undefined : process.execPath` when constructing its Claude Code SDK query (`node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1133`). In a packaged Electron app with the Fuse `RunAsNode: false` (see `forge.config.js:41`), `process.execPath` resolves to the Gremllm Electron binary, which refuses to run arbitrary JS. A `utilityProcess` child inherits the same `process.execPath` — so **Option A does not solve runtime resolution; it inherits the same failure mode as today's broken code**.

Override surface in the pinned adapter:
- `CLAUDE_CODE_EXECUTABLE` env → sets `pathToClaudeCodeExecutable` (`acp-agent.js:1134-1135`). Points at the *target* JS entry; still needs a runtime.
- `CLAUDE_AGENT_ACP_IS_SINGLE_FILE_BUN` env → triggers `isStaticBinary()` branch (`acp-agent.js:38-44`), which leaves `executable` undefined and uses `@anthropic-ai/claude-agent-sdk/embed` as the CLI path (precompiled single-file binary).
- In-code comment at `acp-agent.js:1131-1132` documents that `executable` accepts an absolute path: "passing an absolute path here works to find Zed's managed node version." This is Zed's documented-in-code pattern for packaged runtime control.
- `settingSources: ["user","project","local"]` at `acp-agent.js:1112` is spread *before* `...userProvidedOptions`, so a caller-provided `_meta.claudeCode.options.settingSources: []` overrides it — useful for reducing SDK-side watcher pressure during the spike.

**Consequence for the plan:** the A-vs-C host-model question is secondary to the runtime-resolution question. Both A and C must resolve the same underlying problem ("what interpreter runs Claude Code CLI in our packaged app"). C is the simpler substrate to probe that question on, and the POC already works at the library-integration layer.

Viable set after research and primary-source review: **C (in-process)** as the lead substrate, **A (utilityProcess)** as the fallback if C's library behavior is unacceptable in main. B enters the picture if *runtime resolution* itself proves unsolvable in-Electron.
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

### R6. `claude-agent-acp` runtime-resolution surface *(primary-source, already reviewed)*

Review of the pinned `claude-agent-acp@0.29.2` source established the `executable: process.execPath` hardcoding, the `CLAUDE_CODE_EXECUTABLE` / `CLAUDE_AGENT_ACP_IS_SINGLE_FILE_BUN` override envs, the `settingSources` override path, and the in-code Zed-managed-Node pattern comment. See Context reframing above. No further web research needed on this item before Spike 1 — the spike itself produces the empirical runtime-resolution answer.

**Tells us:** the override surface we have available. Shapes Spike 1's first step.

**Research stop condition:** when R1–R4 converge on a direction, stop. R5 only runs if B becomes a contender. R6 is already complete.

## Spike 1 Findings (2026-04-21)

Runtime-resolution question is closed. Spike branch: `spike/in-process-acp-host`.

### What actually works

**Two changes required, both necessary:**

1. `forge.config.js`: `FuseV1Options.RunAsNode: true`
   — Allows the packaged Electron binary to be used as a Node interpreter when `ELECTRON_RUN_AS_NODE=1` is set in its environment. Without this fuse flip, the Electron binary refuses to run arbitrary JS regardless of env vars.

2. `_meta.claudeCode.options.env: { ELECTRON_RUN_AS_NODE: "1" }` on every `newSession` / `unstable_resumeSession` call.
   — The adapter merges `userProvidedOptions?.env` into the child-spawn env (`acp-agent.js:1115`). This propagates the flag into every Claude CLI subprocess. Without it, each spawn boots a full Electron window instead of a Node interpreter — the app cascades new windows on session create.

### Corrections to the original analysis

**`_meta.claudeCode.options.executable` is NOT a working override.**
The original analysis (and probe plan) claimed the adapter spreads `...userProvidedOptions` *after* its `executable:` default, making `_meta.claudeCode.options.executable` a public override path. The code does the opposite: `...userProvidedOptions` is spread at line 1114, then `executable: process.execPath` is hardcoded at line 1133 — after the user spread — so user-provided values are overwritten. Zed's "absolute path" pattern documented in the adapter comment (`acp-agent.js:1131-1132`) is only reachable by setting `executable` in the SDK's `query()` options directly, not via `_meta`.

**Option E (`RunAsNode: true`) is not a pure regression.**
The deferred-options section called `RunAsNode: true` a security-posture regression with no gain over A/C/B. In practice it is the enabling condition for Option C: `RunAsNode: true` + `ELECTRON_RUN_AS_NODE=1` in the child env is what lets `process.execPath` work as a Node interpreter. The security trade-off is real (any process that receives `ELECTRON_RUN_AS_NODE=1` can run arbitrary JS via the Electron binary), but it is gated by code-signing in production and is strictly narrower than shipping a bundled Node runtime (Option B).

### asar loading

SDK cli.js resolves to `app.asar/node_modules/@anthropic-ai/claude-agent-sdk/cli.js` and loads correctly in the `ELECTRON_RUN_AS_NODE=1` child. Electron's asar fs hooks remain active in that mode. No `asarUnpack` entries required for the SDK path.

### Status

Runtime resolution ✓. Main-process stability verification (parent-plan Steps 5/6: `settingSources` plumbing, fd stability, session-resume, host-tooling independence) still pending.

---

## Spike 1: Harden In-Process ACP (Option C) *(run first)*

### Goal

Answer two questions the plan cannot resolve from code-reading alone:

1. **Runtime resolution:** what override makes `claude-agent-acp`'s internal Claude Code CLI launch succeed in a packaged Electron app where `process.execPath` is the Electron binary and `RunAsNode` is fused off?
2. **Main-process hosting stability:** once runtime resolution is solved, does the adapter library behave well in Electron's main process for full real sessions?

The POC already cleared the library-integration layer; the unknowns are packaged-runtime resolution and long-session stability.

### Hypothesis being tested

> With a suitable `executable` / `pathToClaudeCodeExecutable` / `isStaticBinary` override strategy, `claude-agent-acp` can run inside Electron's main process (via `ClaudeAcpAgent` + `AgentSideConnection` + paired `TransformStream`s) for a full real session without fd leaks, event-loop pauses, or environment-assumption failures.

### Scope

1. **Runtime-resolution probe (first, before any host wiring).** In packaged mode, reproduce the default `spawn` failure and capture the exact executable/args the adapter attempts. Enumerate the override strategies available from `acp-agent.js:1133-1138`:
   - `CLAUDE_CODE_EXECUTABLE=<path>` pointing at a resolvable JS entry (still needs an interpreter — only useful if combined with strategy 2 or 3).
   - `executable=<absolute path to Node>` (Zed's documented-in-code pattern at `acp-agent.js:1131-1132`; requires bundling Node, B-adjacent).
   - `CLAUDE_AGENT_ACP_IS_SINGLE_FILE_BUN=1` + `@anthropic-ai/claude-agent-sdk/embed` (precompiled single-file binary; verify the pinned `@anthropic-ai/claude-agent-sdk` version ships this `embed` entry).
   Pick the lowest-effort strategy that works; document the decision.
2. Build a minimal main-process ACP host using the POC shape: `ClientSideConnection` ↔ paired `TransformStream`s ↔ `AgentSideConnection` ↔ `ClaudeAcpAgent`.
3. Wire it behind a feature flag in [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) so dev mode can toggle between subprocess and in-process; default off until the spike passes.
4. Reduce nondeterminism during the spike by passing `settingSources: []` via `_meta.claudeCode.options` — `acp-agent.js:1112` spreads `userProvidedOptions` after the default, so this override lands. Removes SDK-side filesystem watcher load; the adapter's own `SettingsManager` watchers (`settings.js:153`) remain.
5. Verify `claude-agent-acp` PR #454 (watcher leak fix) is present in pinned `0.29.2`; patch or upgrade if not.
6. Verify dev mode: real session with `document.md` read, pending-diff streaming, multi-turn, resume-session.
7. Verify packaged mode: install to `/Applications`, launch from Finder, run the same session end-to-end.

### Exit criteria (must all pass)

- **Runtime resolution:** a named override strategy makes the packaged launch succeed end-to-end, with the exact config captured in the spike notes (env vars, resource paths, `asarUnpack` entries).
- Clean session ≥10 minutes of real document work; no crashes, no event-loop stalls.
- Watcher fd ceiling measured: known origin (adapter `SettingsManager` + SDK settings), known lifecycle (per-session create/dispose), documented bound.
- fd count stable across **N full sessions** (create + tear down cycle), not just N prompts within one session.
- No regression in pending-diff rendering (`schema.codec` normalization still produces correct shape).
- `acp/resume-session` works across app restarts in packaged mode.

### What we'd learn

- Whether a single override flag (likely `isStaticBinary` or a bundled Node + `executable` path) unblocks packaged launch, and what packaging/`asarUnpack` entries that strategy requires.
- Whether main-process hosting is stable enough to ship. If yes → pick C and stop.
- **Failure-mode routing for the next step:**
  - If runtime resolution itself proves unsolvable in-Electron → pivot to **Option B** (explicit runtime control: bundled Node, or static Claude binary). **Not Spike 2 (A)** — A inherits the same `process.execPath` problem.
  - If runtime resolution works but main-process hosting is unacceptable (event-loop pauses, fd pressure past PR #454) → **then** run Spike 2 (A) for the OS-process isolation it provides.

### Rollback

Feature flag defaults off; subprocess path remains untouched.

## Spike 2: `utilityProcess`-Hosted ACP (Option A) *(run only if Spike 1 fails on main-process library stability, not on runtime resolution)*

### Goal

Determine whether moving the adapter out of the main process (into a `utilityProcess` child) provides enough isolation value to justify the transport-adapter cost, *given* that Spike 1 already solved runtime resolution.

Note: this spike is only worth running if Spike 1 revealed unacceptable main-process library behavior. A `utilityProcess` child inherits the same `process.execPath` as the parent (`acp-agent.js:1133`), so it does not by itself solve packaged runtime resolution — Spike 1's override strategy is a prerequisite.

### Hypothesis being tested

> A `utilityProcess` entrypoint hosting `ClaudeAcpAgent` can bridge its NDJSON streams to `MessagePort` messages with a transport adapter small enough (≤~100 lines) that A's incremental cost over C is worth paying for the OS-process isolation benefit.

### Scope

1. Create a packaged child entrypoint (JS) that constructs `ClaudeAcpAgent` and exposes its ACP transport as a MessagePort-backed stream pair.
2. `utilityProcess.fork()` it from the main process; set up MessagePort.
3. Adapt the main-process ACP client to feed `ClientSideConnection` a `ReadableStream`/`WritableStream` pair that reads/writes MessagePort messages instead of stdin/stdout bytes.
4. Apply Spike 1's runtime-resolution override strategy inside the child entrypoint (same `executable` / env fix, now in the child context).
5. Verify dev mode first: real session with document.md read, pending-diff streaming, multi-turn, resume-session.
6. Verify packaged mode: install to `/Applications`, launch from Finder, run the same session end-to-end.
7. Determine empirically whether the utility-process entrypoint loads from `app.asar` or needs `asarUnpack`.

### Exit criteria (must all pass)

- Full session works through MessagePort transport in packaged mode.
- Transport adapter ≤~100 lines total (main side + child side). This is the **cost bound** — if it blows past, "low transport cost" is falsified.
- Utility-process child survives parent-process signals correctly (clean shutdown, restart).
- Asar-loading question has a definitive answer (works / needs unpack / needs bundling step).
- No regression in pending-diff rendering (`schema.codec` normalization still produces correct shape).
- `acp/resume-session` works across app restarts in packaged mode.

### What we'd learn

- The real answer to "is A a meaningful rewrite, or a clean refactor?"
- Whether Electron's officially-recommended primitive fits the ACP bridge shape once runtime resolution is already in hand.
- If A passes, we ship — no further spikes needed.

### Rollback

Spike runs on a branch; no production paths touched until a decision is made.

## What We Explicitly Defer

- **Option D (`worker_threads`)** — deferred. Workers share the parent OS process, so they do not isolate the watcher fd pressure R4 identified, and they inherit `process.execPath` the same way `utilityProcess` does, so they do not help runtime resolution either. Sync-crash containment is D's only remaining hedge and is not a flagged risk.
- **Option B** — now a first-class fallback, not a last resort. Triggered specifically by Spike 1 failing on runtime resolution. If B opens, prefer the bundled-Node-runtime + JS-entrypoint variant (Zed precedent, R1 + in-code comment at `acp-agent.js:1131-1132`). Bun and Node SEA are not priority variants. R5 research runs at that point.
- **Option E (re-enable `RunAsNode`)** — security-posture regression, no gain over A/C/B.
- **Option F (external tooling prerequisite)** — already ruled out by self-containment direction.

## Decision Framework (after spikes)

R1–R4 research and primary-source review of the pinned adapter are complete. Apply in order:

1. **Spike 1 (C) passes runtime resolution and main-process stability** → pick C. Ship. Record the exact override strategy (env vars, resource paths, `asarUnpack` entries) as the integration contract.
2. **Spike 1 passes runtime resolution but fails main-process stability** (event-loop pauses, fd pressure past PR #454's fix, or other library-in-main pathologies) → run Spike 2 (A). The runtime-resolution override strategy from Spike 1 carries over directly; A adds OS-process isolation on top.
3. **Spike 1 fails runtime resolution** (no override strategy works in a packaged Electron app) → pivot to **Option B** (explicit runtime control: bundled Node runtime + JS entrypoint, or static Claude binary). Run R5 research then. **Do not run Spike 2 (A)** in this branch — A inherits the same `process.execPath` failure mode and cannot rescue runtime resolution on its own.

Retired from the earlier framing: "Zed precedent ⇒ A-first." Zed's precedent supports *managed subprocess + controlled runtime*, which maps to B (runtime control as a first-class axis), not to `utilityProcess` + MessagePort.

## Estimated Effort

- Research: complete (R1–R4 done; R6 primary-source review done).
- Spike 1 (C): ~1–2 days — runtime-resolution probe is the new unknown; POC covers library integration; packaged verification adds variance.
- Spike 2 (A): ~1–2 days — only runs in the "runtime resolution works but main-process hosting is unstable" branch. Transport adapter (~100 line bound) + packaging variance.

**Sequencing note:** run Spike 1 (C) first. If it passes on both runtime resolution and stability, stop and ship C. If it fails on stability only, run Spike 2 (A). If it fails on runtime resolution, pivot to B (do not run Spike 2).

## Critical Files

- [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js) — current subprocess launch site; both spikes modify or guard this.
- [src/gremllm/main/effects/acp.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/effects/acp.cljs) — ACP connection lifecycle; bridges Clojure side to the JS bridge.
- [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js) — packaging config; may need `asarUnpack` entries depending on runtime-resolution strategy chosen in Spike 1.
- [src/gremllm/main/core.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/core.cljs) — ACP init site; the "eager init at boot" behavior matters for both spikes' verification.

## Verification

After the chosen option is implemented:

- Run `npm run dev` — full session with document.md edit proposal, accept, resume.
- Run `npm run make`; install `/Applications/Gremllm.app`; launch from Finder (clean PATH); run full session.
- `fs_usage` or `lsof` check across multiple full sessions (create + tear down) to confirm no fd leak. Watcher lifecycle is per-session; per-prompt checks are insufficient.
- Uninstall Homebrew Node temporarily; re-verify packaged launch works (proves no implicit host-tooling dependency).
