# Design: ACP Packaged-App Agent Launch Investigation

**Date:** 2026-04-20
**Status:** Investigating
**Related:** [docs/specs/2026-04-20-acp-packaged-app-runtime-design.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-acp-packaged-app-runtime-design.md), [forge.config.js](/Users/paul/Projects/gremllm/forge.config.js), [src/js/acp/index.js](/Users/paul/Projects/gremllm/src/js/acp/index.js)

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

## Inference From Evidence

- The packaged app is depending on host installation details for Node/npm tooling, even in `"cached"` mode where the agent package itself is already bundled.
- Finder / LaunchServices app launches may provide a PATH that does not include Homebrew-installed Node tools. We have not yet logged the actual PATH from the installed app process, but the failure shape is consistent with that environment difference.
- Even if `npx` happened to exist on the developer machine, treating it as a production runtime dependency is fragile for a signed, packaged desktop app.

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

- a standalone executable
- a helper app / helper binary
- a JS entrypoint plus a packaged runtime capable of executing it

### Advantages

- Keeps a subprocess boundary.
- Avoids PATH lookup and external `npx` dependency.
- Fits Electron's documented `execFile` support for packaged executables better than `spawn("npx", ...)`.

### Tradeoffs

- Introduces packaging and distribution complexity.
- A plain JS file is not enough by itself if there is no reliable runtime to execute it in packaged mode.
- May require asar unpacking, helper-binary signing, or other release-path special handling.

### Open Technical Unknowns

- What concrete runtime would execute the packaged launcher once `RunAsNode` is disabled.
- Whether this path remains acceptable under codesigning / notarization constraints.

## Option C: Host `claude-agent-acp` In-Process

Stop launching the ACP agent as an external process in packaged mode and instead embed the bundled `@agentclientprotocol/claude-agent-acp` package directly inside the Electron main process (or another internal process owned by the app).

### Advantages

- Eliminates the `npx` / PATH problem entirely.
- Reuses code that is already bundled in `app.asar`.
- Avoids subprocess-launch packaging edge cases.

### Tradeoffs

- Collapses the current process boundary between Gremllm and the ACP agent.
- Reduces crash isolation and may complicate resource cleanup.
- Couples Gremllm more directly to the ACP package's library surface and lifecycle assumptions.

### Open Technical Unknowns

- Whether the package's exported library API is stable and sufficient for this use case without relying on CLI-only behavior.
- What adapter is needed to replace the current subprocess stdio transport with an in-process connection.

## Option D: Re-enable `RunAsNode` and Use a Fork-Based Launch

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

## Option E: Keep `npx`, But Treat Host Node/npm As a Prerequisite

Retain the current launch path, but detect missing `npx` at startup and show a clear environment error instead of crashing unexpectedly.

### Advantages

- Smallest implementation change.
- Preserves the current subprocess and ACP transport shape.

### Tradeoffs

- Does not make the packaged app self-contained.
- Keeps startup behavior dependent on PATH and host tool installation.
- Produces a weaker first-run experience for end users and testers.
- Leaves production behavior coupled to developer-machine conventions.

### Open Technical Unknowns

- Whether this is acceptable product behavior for a packaged macOS desktop app at all.

## Comparison

| Criterion | A: `utilityProcess` | B: packaged launcher + `execFile` | C: in-process ACP | D: re-enable `RunAsNode` | E: keep `npx` prerequisite |
|-----------|---------------------|-----------------------------------|-------------------|--------------------------|----------------------------|
| Removes external PATH dependency | Yes | Yes | Yes | Yes | No |
| Preserves subprocess boundary | Yes | Yes | No | Yes | Yes |
| Fits current fuse posture | Yes | Yes | Yes | No | Yes |
| Requires ACP transport changes | High | Medium | Medium | Low to medium | None |
| Adds packaging complexity | Medium | High | Low to medium | Medium | Low |
| Keeps app self-contained | Likely | Likely | Yes | Likely | No |

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
- If we pursue a packaged-launcher design, what runtime executes it once `RunAsNode` remains disabled?
- Is preserving a hard process boundary a requirement, or merely a preference carried forward from the current implementation?
- Is "packaged app is self-contained" a product requirement for this release path, or is an external toolchain prerequisite still considered acceptable?

## Next Validation

Before selecting an option, the next round of evidence should ideally answer:

- installed-app runtime PATH logging at ACP init time
- whether a minimal `utilityProcess` proof of concept can host the agent bridge shape we need
- whether a packaged absolute-path launcher can be executed cleanly under current fuse and asar constraints
- whether the in-process ACP library path is viable without depending on unstable internal APIs
