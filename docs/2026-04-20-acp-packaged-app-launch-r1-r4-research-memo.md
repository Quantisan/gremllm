# Research Memo: ACP Packaged-App Launch (R1-R4)

**Date:** 2026-04-20
**Status:** Research findings only
**Related:** [Investigation spec](/Users/paul/Projects/gremllm/.worktrees/packaged-agent-launch/docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md), [Research and spikes plan](/Users/paul/Projects/gremllm/.worktrees/packaged-agent-launch/docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md)

This memo records the findings from research items `R1` to `R4` in the packaged-app agent launch plan. It intentionally does **not** choose an option or synthesize a recommendation. Where a point is not directly stated by a source, it is labeled as an inference.

## R1. Zed's Packaging of ACP Agents

### Findings

- Zed documents external agents as processes it runs in the background and talks to over ACP, not as libraries embedded into the editor process.
- For Claude Agent, Zed documents a managed first-use install of `@zed-industries/claude-agent-acp`, and states that this managed adapter includes a vendored Claude Code CLI even if Claude Code is also installed globally.
- Zed supports overriding the Claude executable via `CLAUDE_CODE_EXECUTABLE`, which indicates the adapter ultimately launches an executable rather than exposing only an in-process library surface.
- For Codex, Zed likewise documents a managed first-use install of `codex-acp`.
- The ACP registry entry current on **April 20, 2026** lists `claude-acp` as an `npx` distribution and `codex-acp` as a binary distribution with an `npx` fallback.
- Zed's runtime code supports both registry binary agents and registry `npx` agents.
- Zed's node runtime code prefers a working system Node/npm, but also supports downloading and using a Zed-managed Node runtime when allowed.

### Evidence

- Zed docs say Gemini CLI is run in the background and spoken to over ACP, and say for Claude Agent: "Under the hood, Zed runs the Claude Agent SDK, which runs Claude Code under the hood, and communicates to it over ACP, through a dedicated adapter."
  Source: [Zed external agents docs](https://zed.dev/docs/ai/external-agents)
- The same docs say: "The first time you create a Claude Agent thread, Zed will install `@zed-industries/claude-agent-acp`" and that Zed will use this managed version, which includes a vendored Claude Code CLI.
  Source: [Zed external agents docs](https://zed.dev/docs/ai/external-agents)
- The same docs document `CLAUDE_CODE_EXECUTABLE` as an override.
  Source: [Zed external agents docs](https://zed.dev/docs/ai/external-agents)
- The current ACP registry lists `claude-acp` with `distribution.npx` and `codex-acp` with `distribution.binary` plus `distribution.npx`.
  Source: [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json)
- Zed's agent server store handles both `RegistryAgent::Binary` and `RegistryAgent::Npx`, constructing `LocalRegistryArchiveAgent` or `LocalRegistryNpxAgent` accordingly.
  Source: [agent_server_store.rs](https://raw.githubusercontent.com/zed-industries/zed/main/crates/project/src/agent_server_store.rs)
- Zed's node runtime code includes `allow_binary_download`, `run_npm_subcommand`, and managed Node download paths from `nodejs.org`.
  Source: [node_runtime.rs](https://raw.githubusercontent.com/zed-industries/zed/main/crates/node_runtime/src/node_runtime.rs)

### Unresolved Gaps

- The research did not inspect a packaged Zed app artifact directly.
- This research did not establish whether Zed ever embeds an ACP agent library in-process for any agent. The documented path is process-based.

## R2. Other Electron-Based Agent Apps

### Findings

- Continue and Cline publicly document subprocess-style MCP transports and also show their core agent logic running in-process in the VS Code extension host.
- Continue's VS Code extension entrypoint is `./out/extension.js`, and its extension source constructs `InProcessMessenger` and `Core`.
- Cline's extension entrypoint is `./dist/extension.js`, and its MCP source imports and constructs `StdioClientTransport`, `SSEClientTransport`, and `StreamableHTTPClientTransport`.
- Cursor and Windsurf publicly document MCP transports, including `stdio`, but this research did not find direct public evidence for how their core agent runtime is packaged or isolated.
- This research did not find evidence that any of the surveyed products ship a Bun-compiled helper for the core agent runtime.

### Evidence

- Continue's extension package sets `"main": "./out/extension.js"`.
  Source: [Continue VS Code package](https://raw.githubusercontent.com/continuedev/continue/main/extensions/vscode/package.json)
- Continue's extension source imports `InProcessMessenger` and constructs both `new InProcessMessenger(...)` and `new Core(...)`.
  Source: [Continue VsCodeExtension.ts](https://raw.githubusercontent.com/continuedev/continue/main/extensions/vscode/src/extension/VsCodeExtension.ts)
- Continue's MCP docs show `stdio` MCP configuration using `command` and `args`.
  Source: [Continue MCP docs](https://docs.continue.dev/customize/deep-dives/mcp)
- Cline's package sets `"main": "./dist/extension.js"`.
  Source: [Cline package](https://raw.githubusercontent.com/cline/cline/main/package.json)
- Cline's MCP source imports `StdioClientTransport`, `SSEClientTransport`, and `StreamableHTTPClientTransport`, and constructs the stdio transport with command-based configuration.
  Source: [Cline McpHub.ts](https://raw.githubusercontent.com/cline/cline/main/src/services/mcp/McpHub.ts)
- Cline's MCP docs describe `stdio`, `SSE`, and `streamable HTTP` transport mechanisms.
  Source: [Cline MCP transport docs](https://docs.cline.bot/mcp/mcp-transport-mechanisms)
- Cursor's MCP docs describe local `stdio` configuration with `command`, `args`, and `env`.
  Source: [Cursor MCP docs](https://docs.cursor.com/context/model-context-protocol)
- Windsurf's MCP docs describe `stdio`, `Streamable HTTP`, and `SSE` configuration with command-based examples.
  Source: [Windsurf MCP docs](https://docs.windsurf.com/windsurf/cascade/mcp)

### Inference

- Continue and Cline provide direct evidence that editor-hosted agent logic plus subprocess MCP integrations is a real pattern in adjacent tools.
- Cursor and Windsurf provide direct evidence for subprocess MCP support, but not for their internal process model for the core agent runtime.

### Unresolved Gaps

- No direct public packaging evidence was found for Cursor's core agent runtime.
- No direct public packaging evidence was found for Windsurf's core agent runtime.
- No direct evidence was found for Bun-compiled helpers in this product set.

## R3. `utilityProcess` and Bidirectional Stream Patterns

### Findings

- Electron's `utilityProcess` supports bidirectional message passing through `postMessage` and `process.parentPort`, but does not expose writable stdin for the child.
- ACP explicitly allows custom transports as long as they preserve ACP's JSON-RPC lifecycle and bidirectional message exchange.
- The ACP SDK connection types operate over a generic stream abstraction rather than requiring OS stdio specifically.
- Electron's ASAR docs document important packaging constraints: `cwd` cannot point inside an ASAR archive, and binaries inside ASAR are a special case that generally require unpacking.
- This research did not find an official Electron example of NDJSON-over-`MessagePort` specifically for `utilityProcess`.

### Evidence

- Electron documents `utilityProcess.fork()` and shows `child.postMessage(...)` with transferred `MessagePortMain` objects; it also documents that `stdin` is ignored while `stdout` and `stderr` are available.
  Source: [Electron utilityProcess docs](https://www.electronjs.org/docs/latest/api/utility-process)
- Electron documents MessagePort-based patterns and notes lifecycle details such as starting the port on the main side.
  Source: [Electron MessagePorts tutorial](https://www.electronjs.org/docs/latest/tutorial/message-ports/)
- ACP transport docs state that ACP is transport-agnostic and may be implemented over custom transports that preserve bidirectional JSON-RPC exchange.
  Source: [ACP transport docs](https://agentclientprotocol.com/protocol/transports)
- Electron's ASAR docs say that `cwd` cannot be set inside ASAR and that unpacking is the usual workaround for executable/native cases.
  Source: [Electron ASAR docs](https://www.electronjs.org/docs/latest/tutorial/asar-archives)

### Inference

- A `MessagePort` to stream adapter appears feasible at the protocol boundary, but the adapter would be application-owned rather than a documented off-the-shelf pattern.
- A JavaScript `utilityProcess` entrypoint inside `app.asar` may be workable, but any binary/helper path should be treated as an unpacking question rather than assumed to work unchanged.

### Unresolved Gaps

- No official end-to-end example was found for NDJSON or JSON-RPC over `MessagePort` in a `utilityProcess`.
- This research did not establish a documented ACP SDK helper for `MessagePort` transport adaptation.
- This research did not directly verify whether a packaged `utilityProcess` entrypoint should live in `app.asar` or `app.asar.unpacked`.

## R4. `claude-agent-acp` EMFILE / Settings Watcher Origin

### Findings

- `claude-agent-acp` creates a `SettingsManager` during session creation, not at import time.
- `SettingsManager.initialize()` sets up watchers over multiple settings file locations.
- The settings manager code watches user, project, local, and managed settings paths.
- `claude-agent-acp` passes `settingSources: ["user", "project", "local"]` into the Anthropic SDK path during session creation.
- Anthropic's SDK docs document `settingSources` as the mechanism for disabling user/project/local filesystem settings, including `settingSources: []`.
- This research did not find a documented adapter-level option or environment variable in `claude-agent-acp` to disable its own `SettingsManager` watcher behavior.
- Upstream issue history shows watcher-related exhaustion as a real reported problem, and `claude-agent-acp` has already fixed at least one watcher-leak bug.

### Evidence

- In `claude-agent-acp`, `createSession(...)` constructs `new SettingsManager(params.cwd, ...)`.
  Source: [acp-agent.ts](https://github.com/agentclientprotocol/claude-agent-acp/blob/main/src/acp-agent.ts)
- The same file sets `settingSources: ["user", "project", "local"]`.
  Source: [acp-agent.ts](https://github.com/agentclientprotocol/claude-agent-acp/blob/main/src/acp-agent.ts)
- In `settings.ts`, `initialize()` calls `setupWatchers()`, and `setupWatchers()` includes user, project, local, and managed settings paths and calls `fs.watch(...)`.
  Source: [settings.ts](https://github.com/agentclientprotocol/claude-agent-acp/blob/main/src/settings.ts)
- Anthropic's TypeScript SDK docs describe `settingSources`, including the ability to disable user/project/local settings by supplying an empty list.
  Source: [Anthropic Agent SDK TypeScript docs](https://code.claude.com/docs/en/agent-sdk/typescript)
- Anthropic issue `#7624` reports startup failure from settings file watcher exhaustion and is closed as `not planned`.
  Source: [anthropics/claude-code#7624](https://github.com/anthropics/claude-code/issues/7624)
- `claude-agent-acp` PR `#454` fixes a session leak that left active `fs.watch()` descriptors behind.
  Source: [claude-agent-acp PR #454](https://github.com/agentclientprotocol/claude-agent-acp/pull/454)

### Inference

- The local `EMFILE` warning is consistent with real watcher behavior in the adapter and not solely with a synthetic sandbox artifact.
- Because `claude-agent-acp` runs its own settings watcher and also opts into SDK filesystem setting sources, more than one settings-related mechanism may be active during a session.

### Unresolved Gaps

- This research did not establish whether `settingSources: []` would remove all SDK-side watcher behavior in the relevant runtime path.
- This research did not find a documented non-fork way to disable the adapter's own watcher behavior.

## Notes on Scope

- This memo records research findings only.
- No option selection, prioritization, or spike outcome synthesis is included here.
