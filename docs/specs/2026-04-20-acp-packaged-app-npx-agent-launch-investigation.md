# Design: ACP Packaged-App Agent Launch Investigation

**Date:** 2026-04-20
**Status:** Historical option-space record; final outcome captured in the closeout note
**Related:** [docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md), [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js), [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js)
**Closeout:** [docs/2026-04-21-acp-packaged-host-closeout.md](/Users/paul/Projects/gremllm/.worktrees/spike-in-process-acp-host/docs/2026-04-21-acp-packaged-host-closeout.md)

## Goal

Capture the packaged-app ACP agent launch failure that appears after the earlier ACP bridge packaging fix, record what is verified so far, and enumerate the viable option space without selecting a solution yet.

## Verified Problem

- After installing the app to `/Applications`, launch fails with an uncaught exception:

```text
Error: spawn npx ENOENT
```

- The ACP bridge still launches the agent process through `npx` in [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js):
  - cached mode: `spawn("npx", ["claude-agent-acp"], ...)`
  - latest mode: `spawn("npx", ["--yes", "--package=@agentclientprotocol/claude-agent-acp@<version>", "--", "claude-agent-acp"], ...)`
- Packaged app startup initializes ACP eagerly from [src/gremllm/main/core.cljs](/Users/paul/Projects/gremllm/src/gremllm/main/core.cljs), so this subprocess launch happens during app boot rather than on first user prompt.
- Per the Node.js `child_process.spawn()` docs, `ENOENT` is emitted when the command does not exist or the requested `cwd` does not exist. In this case the command is `npx`, not a workspace-relative path.

## What We Verified

- The packaged `app.asar` already contains the bundled ACP agent package:
  - `/node_modules/@agentclientprotocol/claude-agent-acp/package.json`
  - `/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js`
- The packaged `app.asar` also contains the current bridge module at `/src/js/acp/index.js`.
- This is therefore not the same class of problem as the earlier `resources/acp` symlink / asar resolution failure. The agent package payload is present in the packaged artifact.
- On this machine, `npx` resolves to `/opt/homebrew/bin/npx`, which is outside the minimal macOS system PATH.
- A minimal local reproduction matches the installed-app error exactly:

```bash
node - <<'EOF'
const {spawn}=require('node:child_process');
const cp=spawn('npx',['--version'],{env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin'}});
cp.on('error', err => console.log(err.message));
setTimeout(()=>process.exit(0),500);
EOF
```

- That reproduction emits:

```text
spawn npx ENOENT
```

- The bundled `@agentclientprotocol/claude-agent-acp` package is not CLI-only. Its published library entrypoint exports `ClaudeAcpAgent`, `runAcp`, and related helpers from `dist/lib.js`.
- The bundled `@agentclientprotocol/sdk` transport layer is not stdio-specific. `ndJsonStream()` accepts generic Web `ReadableStream` / `WritableStream` pairs, and the SDK's own tests construct client/agent pairs entirely with in-memory `TransformStream`s.
- A local proof of concept in this repo successfully completed ACP `initialize` and `newSession` in a single Node process using `ClientSideConnection`, `AgentSideConnection`, `ClaudeAcpAgent`, and paired `TransformStream`s. No `npx` or subprocess launch was involved. The probe emitted non-fatal `EMFILE` settings-watcher warnings in this sandbox, but ACP handshake and session creation succeeded.
- The upstream Anthropic SDK and ACP adapter already contain explicit single-file Bun support surfaces:
  - `@anthropic-ai/claude-agent-sdk/embed` documents Bun `--compile` usage and extracts the embedded CLI from `$bunfs` so it can be spawned by child processes.
  - `claude-agent-acp` has a `CLAUDE_AGENT_ACP_IS_SINGLE_FILE_BUN` code path when resolving the Claude CLI.

## Inference From Evidence

- The packaged app is depending on host installation details for Node/npm tooling, even in `"cached"` mode where the agent package itself is already bundled.
- Finder / LaunchServices app launches may provide a PATH that does not include Homebrew-installed Node tools. We have not yet logged the actual PATH from the installed app process, but the failure shape is consistent with that environment difference.
- Even if `npx` happened to exist on the developer machine, treating it as a production runtime dependency is fragile for a signed, packaged desktop app.
- The in-process ACP path is no longer merely theoretical. The current package set already supports it with the published library API and the existing ACP SDK transport primitives.
- The "packaged launcher" path is also more concrete than originally stated: at least one upstream-aligned helper-binary approach exists today (single-file Bun), and a "ship your own Node runtime" variant is mechanically possible even if heavier.

## Resolution of Open Questions

The following open questions from this investigation were settled through design review:

- **Is preserving a hard process boundary a requirement, or merely a preference?** Strong preference, not a hard requirement. In-process hosting is acceptable if the ACP library is well-behaved in a real session and a hard-boundary option's transport cost is material.
- **Is "packaged app is self-contained" a product requirement?** Yes. Option F is ruled out on that basis.
- **Which concrete launcher variant is best if we pursue Option B?** Deferred pending research. The B-variant question is gated on what other Electron-based agent apps actually ship (web research items R1, R2 in the plan).
- **Options E and F status:** Both eliminated. E reverses an explicit security-hardening choice with no gain over A/B/D. F conflicts with the self-containment requirement.

Active candidates for validation: **A (utilityProcess)**, **C (in-process)**, **D (worker thread)**. Option B held in reserve pending research and spike outcomes.

## Scope

This investigation covers only the packaged-app boundary where Gremllm locates and launches the ACP agent process.

It does not cover:

- ACP protocol changes
- prompt construction changes
- renderer behavior changes
- diff handling / document application flow
- signing or notarization policy beyond what directly affects agent launch
- non-macOS packaging

## Electron and Node Runtime Constraints

These constraints are verified against official Electron and Node docs.

References:

- [Electron Fuses](https://www.electronjs.org/docs/latest/tutorial/fuses)
- [Electron utilityProcess](https://www.electronjs.org/docs/latest/api/utility-process)
- [Electron ASAR Archives](https://www.electronjs.org/docs/latest/tutorial/asar-archives)
- [Electron app](https://www.electronjs.org/docs/latest/api/app)
- [Electron process](https://www.electronjs.org/docs/latest/api/process)
- [Node.js `child_process.spawn()`](https://nodejs.org/download/release/v22.19.0/docs/api/child_process.html)
- [Node.js worker_threads](https://nodejs.org/download/release/latest-v22.x/docs/api/worker_threads.html)
- [Node.js Single Executable Applications](https://nodejs.org/download/release/latest-jod/docs/api/single-executable-applications.html)
- [Bun single-file executables](https://bun.sh/docs/bundler/executables)

### 1. `spawn()` is PATH-sensitive

Node documents that `child_process.spawn()` emits `ENOENT` when the command does not exist. A bare `"npx"` launch therefore depends on PATH resolution at runtime rather than on packaged app contents.

### 2. Current fuse policy disables `RunAsNode`

[forge.config.js](/Users/paul/Projects/gremllm/forge.config.js) sets:

```js
[FuseV1Options.RunAsNode]: false
```

Electron's fuses documentation states that with `runAsNode` disabled, `child_process.fork` in the main process "will not function as expected" because it depends on `ELECTRON_RUN_AS_NODE`. Electron recommends `utilityProcess` for standalone Node child-process use cases instead.

### 3. `utilityProcess` is not a drop-in stdio replacement

Electron documents `utilityProcess.fork()` as the supported Node child-process primitive in packaged apps, but its stdio model only allows configuring `stdout` and `stderr`. `stdin` must remain ignored. That means it cannot directly replace the current ACP bridge shape, which assumes bidirectional NDJSON over stdin/stdout pipes.

### 4. ASAR limits subprocess execution patterns

Electron's ASAR docs state:

- working directory cannot be set to a directory inside an asar archive
- only `execFile` is supported for executing binaries inside ASAR archives
- `spawn` and `exec` are not reliable ways to target an asar-contained executable path

This matters for any design that tries to execute a packaged launcher directly from `app.asar`.

### 5. Packaged resource paths are available

Electron exposes both:

- `app.getAppPath()` - current application directory
- `process.resourcesPath` - packaged resources directory

Those APIs are the likely starting point for any option that resolves an absolute packaged launcher path instead of relying on PATH lookup.

## Option A: Use `utilityProcess` for the Agent Child

Create a dedicated packaged child entrypoint for ACP agent hosting and launch it via Electron's `utilityProcess.fork()`. Replace the current stdio transport with a `MessagePort`-based bridge or another `utilityProcess`-compatible transport.

### Advantages

- Aligns with Electron's documented recommendation when `RunAsNode` is disabled.
- Preserves a process boundary between the main app and the ACP agent.
- Removes the external `npx` / PATH dependency from packaged startup.
- Keeps the security posture implied by the current fuse configuration.

### Tradeoffs

- Largest architectural change at the transport layer: ACP is currently wired around stdin/stdout NDJSON.
- Requires a bridge layer between Electron `MessagePort` semantics and the ACP SDK's current connection setup.
- Utility process lifecycle and logging behavior would differ from the current `child_process.spawn()` model.

### Open Technical Unknowns

- Whether the utility-process entrypoint can live inside `app.asar` or should be unpacked explicitly.
- How much of the existing bridge can be reused once stdin is no longer available.

## Option B: Ship a Real Packaged Launcher and Run It via Absolute Path

Package a concrete agent launcher outside `app.asar` and execute it via `execFile` using an absolute path derived from `process.resourcesPath` or `app.getAppPath()`.

Examples of what "real launcher" could mean:

- a standalone Bun-compiled executable for the ACP adapter
- a helper app / helper binary
- a JS entrypoint plus a packaged runtime capable of executing it
- a Node single-executable-app wrapper after bundling to a CommonJS entrypoint

### Advantages

- Keeps a subprocess boundary.
- Avoids PATH lookup and external `npx` dependency.
- Fits Electron's documented `execFile` support for packaged executables better than `spawn("npx", ...)`.

### Tradeoffs

- Introduces packaging and distribution complexity.
- A plain JS file is not enough by itself if there is no reliable runtime to execute it in packaged mode.
- May require asar unpacking, helper-binary signing, or other release-path special handling.
- The variants are not equally attractive:
  - Bun helper: concrete and upstream-aligned, but adds Bun to the release toolchain.
  - Bundled Node runtime + JS entrypoint: straightforward model-wise, but larger and requires shipping/signing an extra runtime.
  - Node SEA wrapper: possible in principle, but Node's official SEA docs still describe the embedded app as a single CommonJS script, which is a poor fit for today's ESM `claude-agent-acp` package without an extra bundling layer.

### Open Technical Unknowns

- What concrete runtime would execute the packaged launcher once `RunAsNode` is disabled.
- Whether this path remains acceptable under codesigning / notarization constraints.

## Option C: Host `claude-agent-acp` In-Process

Stop launching the ACP agent as an external process in packaged mode and instead embed the bundled `@agentclientprotocol/claude-agent-acp` package directly inside the Electron main process (or another internal process owned by the app).

### Advantages

- Eliminates the `npx` / PATH problem entirely.
- Reuses code that is already bundled in `app.asar`.
- Avoids subprocess-launch packaging edge cases.
- Stronger than originally assumed: local evidence shows that ACP handshake and session creation already work with published library APIs and in-memory streams.
- May require less protocol-layer churn than Option A, because the same ACP SDK `ClientSideConnection` / `AgentSideConnection` types can be reused without stdio.

### Tradeoffs

- Collapses the current process boundary between Gremllm and the ACP agent.
- Reduces crash isolation and may complicate resource cleanup.
- Couples Gremllm more directly to the ACP package's library surface and lifecycle assumptions.
- Runs agent work on the app-owned runtime unless we combine this with a worker or utility-process host.
- The local probe surfaced `EMFILE` settings-watcher warnings in this sandbox, which may indicate SDK-side background watchers or may be an artifact of this environment. That needs one more round of validation in the actual app.

### Open Technical Unknowns

- Whether the package's exported library API is stable and sufficient for this use case without relying on CLI-only behavior.
- Whether the settings-watcher warnings seen in the local probe matter in a real app session.

## Option D: Host `claude-agent-acp` In a Node Worker Thread

Create a dedicated `worker_threads` host for the ACP agent and communicate over `MessagePort` (or a small stream adapter) instead of running the agent directly on the Electron main thread.

### Advantages

- Removes the `npx` / PATH dependency without relaxing the current fuse posture.
- Keeps the solution entirely inside the app-bundled Node/Electron runtime.
- Preserves more isolation than main-thread in-process hosting for CPU-heavy work and ordinary JS failures.
- Uses standard Node primitives that are already available in Electron's main-process runtime.

### Tradeoffs

- Still does not preserve a hard OS process boundary the way `utilityProcess` or `execFile` would.
- Requires a custom port/stream bridge, similar in spirit to Option A.
- Introduces worker lifecycle, error-forwarding, and shutdown plumbing that the current design does not need.

### Open Technical Unknowns

- Whether the worker entrypoint should live inside `app.asar` or be unpacked.
- How much real crash/isolation benefit this buys relative to simply hosting in the main process.

## Option E: Re-enable `RunAsNode` and Use a Fork-Based Launch

Change the fuse policy to allow `RunAsNode` again, then launch a bundled JS agent entrypoint using a Node-style child-process API such as `fork`.

### Advantages

- Closest to the current mental model of "spawn a Node child that speaks ACP."
- May reduce transport rewrite work compared with a `utilityProcess` design.

### Tradeoffs

- Reverses an explicit security hardening choice already present in the package configuration.
- Conflicts with Electron's current recommendation to prefer `utilityProcess` when `RunAsNode` is disabled.
- Requires fuse-policy discussion, repackaging, and fresh security review.

### Open Technical Unknowns

- Whether the team is willing to relax the current packaged security posture for this functionality.
- Whether the operational simplicity gain is worth the fuse-policy reversal.

## Option F: Keep External Node/npm Tooling as a Prerequisite

Retain an external-tooling launch path, but treat host Node/npm availability as an explicit prerequisite instead of an implicit assumption.

Variants:

- resolve/import a fuller shell PATH before spawning `npx`
- detect missing `npx` and surface a clear environment error instead of crashing
- defer ACP initialization until first ACP use so the whole app does not fail during boot

### Advantages

- Smallest implementation change.
- Preserves the current subprocess and ACP transport shape.
- May be acceptable as a short-term diagnostic or internal-testing bridge while a self-contained launch mode is developed.

### Tradeoffs

- Does not make the packaged app self-contained.
- Keeps startup behavior dependent on PATH and host tool installation.
- Produces a weaker first-run experience for end users and testers.
- Leaves production behavior coupled to developer-machine conventions.
- PATH-import variants reduce one symptom but not the underlying packaging dependency.
- Lazy init avoids crashing on app boot, but it does not solve the missing-runtime problem.

### Open Technical Unknowns

- Whether this is acceptable product behavior for a packaged macOS desktop app at all.

## Comparison

| Criterion | A: `utilityProcess` | B: packaged launcher + `execFile` | C: in-process ACP | D: worker thread | E: re-enable `RunAsNode` | F: external tooling prerequisite |
|-----------|---------------------|-----------------------------------|-------------------|------------------|--------------------------|----------------------------------|
| Removes external PATH dependency | Yes | Yes | Yes | Yes | Yes | No |
| Preserves hard process boundary | Yes | Yes | No | No | Yes | Yes |
| Fits current fuse posture | Yes | Yes | Yes | Yes | No | Yes |
| Requires ACP transport changes | High | Medium | Low to medium | Medium | Low to medium | None |
| Adds packaging complexity | Medium | High | Low | Low to medium | Medium | Low |
| Keeps app self-contained | Likely | Likely | Yes | Yes | Likely | No |
| Current evidence level | Docs only | Docs + concrete variants | Docs + local POC | Docs only | Docs only | Current broken behavior |

**E** is eliminated (security-posture regression with no gain over A/B/D). **F** is eliminated (conflicts with self-containment requirement). Active candidates: **A, C, D**. **B** held in reserve.

## Decision Criteria

Any eventual solution should:

- launch reliably from an installed `/Applications` app without requiring Homebrew Node/npm tooling
- preserve ACP behavior in development and test modes
- keep `npm run package` and `npm run make` working
- avoid fragile release-only PATH assumptions
- minimize new packaging special cases unless they buy clear runtime robustness

## Open Questions

- What PATH does the installed app actually see at the point where ACP initialization runs?
- Can `utilityProcess.fork()` load an entrypoint directly from `app.asar`, or should that script be unpacked?
- If we pursue a packaged-launcher design, which concrete variant is best: Bun helper, bundled Node runtime, or something else?
- Are the `EMFILE` settings-watcher warnings from the in-process probe a sandbox artifact or a real integration concern?
- Can a worker-thread host give enough isolation to be worthwhile, or is the choice really "main process" versus "real subprocess"?
- Is preserving a hard process boundary a requirement, or merely a preference carried forward from the current implementation?
- Is "packaged app is self-contained" a product requirement for this release path, or is an external toolchain prerequisite still considered acceptable?

## Next Validation

Before selecting an option, the next round of evidence should ideally answer:

- installed-app runtime PATH logging at ACP init time
- whether a minimal `utilityProcess` proof of concept can host the agent bridge shape we need
- whether a packaged Bun helper launched via `execFile` works cleanly under signing / asar constraints
- whether the in-process ACP library path still behaves cleanly when exercised inside the packaged app rather than a standalone Node probe
- whether a worker-thread host buys enough isolation to justify the extra transport plumbing
