# ACP Integration PoC - Learnings

This document captures what we learn as we explore integrating the Agent Client Protocol (ACP) into Gremllm.

## Overview

Agent Client Protocol (ACP) is a JSON-RPC protocol that allows applications to spawn and communicate with Claude Code as an agentic subprocess. The subprocess can use tools to read files, execute commands, and perform other actions on behalf of the application.

**Package:** `@zed-industries/claude-code-acp`
**Protocol Spec:** https://agentclientprotocol.org/
**Implementation Guide**: context/acp_client.md

## Phase 1: Process Lifecycle ✅

**Goal:** Prove we can spawn the ACP subprocess with stdio pipes.

**Key Learnings:**
- Process spawns via `npx @zed-industries/claude-code-acp` with auto-install (3-5s first run, ~1s thereafter)
- stdio config: `["pipe", "pipe", "inherit"]` for stdin/stdout pipes, stderr passthrough
- Environment inheritance includes `ANTHROPIC_API_KEY`
- Clean shutdown via SIGTERM

## Phase 2: NDJSON Protocol ✅

**Goal:** Establish NDJSON connection using SDK's `ClientSideConnection` and complete handshake.

**Key Learnings:**

1. **⚠️ Stream direction matters (CRITICAL):** Naming is from SDK's perspective:
   - `input` = `Writable.toWeb(proc.stdin)` - where SDK writes TO agent
   - `output` = `Readable.toWeb(proc.stdout)` - where SDK reads FROM agent
   - Swapping these causes `getWriter is not a function` errors

2. **Minimal client interface:** Only two methods required:
   - `requestPermission(request)` - Handle permission requests
   - `sessionUpdate(update)` - Receive streaming notifications

3. **Agent capabilities:** Supports image prompts, embedded context, HTTP/SSE MCP servers, session fork/resume (see `context/acp_client.md` for details)

4. **Protocol version:** Agent returns `protocolVersion: 1`, matching SDK's `PROTOCOL_VERSION`

## Phase 3: Integration into Gremllm

### Architectural Decisions

- **Subprocess:** Long-lived (spawn once, reuse for all requests)
- **Sessions:** One per topic (isolated conversation contexts)
- **Capabilities:** Minimal (prompts only, no file system or terminal delegation)
- **Streaming:** IPC events per sessionUpdate
- **JS/CLJS boundary:** Dispatch bridge pattern for event callbacks

### Vertical Slices

| Slice | Goal | Status |
|-------|------|--------|
| **1** | SDK wrapper + dispatch bridge | ✅ Complete |
| **1b** | IPC wiring (renderer → main) | ✅ Complete |
| **2** | Streaming updates to renderer | 🚧 In Progress (events flowing, UI pending) |
| **3** | Session lifecycle (per-topic) | Planned |
| **4** | Renderer UI integration | Planned |

### Slice Dependencies

Slice 1 (SDK) → Slice 1b (IPC) → Slice 2 (streaming) + Slice 3 (sessions) → Slice 4 (UI)

### Implementation Plans

- **Slice 1:** `docs/plans/2026-01-23-acp-slice-1-design.md`
- Future slices: TBD

## Phase 3.1: SDK Wrapper ✅

**Goal:** Wrap ACP SDK in JS module callable from CLJS with event dispatch bridge.

**Implementation:** `resources/acp/` (local npm package), `src/gremllm/main/effects/acp.cljs`

**Key Learnings:**

1. **Dispatch bridge pattern (JS→CLJS):** JS callbacks can't directly call CLJS. Solution:
   ```javascript
   let dispatcher = null;
   export function setDispatcher(fn) { dispatcher = fn; }

   const client = {
     async sessionUpdate(params) {
       if (dispatcher) dispatcher("acp.events/session-update", params);
     }
   };
   ```
   CLJS wires this during initialization to route events into Nexus.

2. **Sessions required:** ACP protocol requires sessions scoped to working directory. API is `prompt(sessionId, text)`, not `prompt(text)`.

3. **Key casing boundary:** JS sends camelCase (`sessionId`), CLJS expects kebab-case (`:session-id`). Coercion needed at boundary.

## Phase 3.2: IPC Wiring ✅

**Goal:** Connect renderer to ACP agent via IPC (`window.electronAPI.acpNewSession()`, `acpPrompt()`).

**Implementation:** `resources/public/js/preload.js`, `src/gremllm/main/{core,actions}.cljs`, `src/gremllm/schema.cljs`

**Key Learnings:**

1. **⚠️ Local npm package pattern (CRITICAL for dev mode):**
   Shadow-cljs `:js-options {:resolve ...}` only works for bundled builds. In watch/dev mode, shadow uses Node's `require()` at runtime. Solution:
   ```
   resources/acp/
   ├── package.json   # {"name": "acp", "main": "index.js"}
   └── index.js       # Module code

   # In project package.json:
   "acp": "file:./resources/acp"
   ```
   npm creates symlink—file changes available without reinstall.

2. **⚠️ CommonJS required:** ES modules (`import`/`export`) cause shim errors in shadow-cljs watch mode. Use CommonJS (`require`/`module.exports`) for local modules in main process.

3. **Lazy initialization:** `newSession()` auto-initializes subprocess on first use—no explicit `initialize()` calls needed.

4. **Streaming vs return values:** ACP responses stream via `sessionUpdate` events. The `prompt()` return is just `{stopReason: 'end_turn'}`—actual content arrives as `agent_message_chunk` events through dispatcher.

5. **IPC correlation pattern:** Preload's `createIPCBoundary` generates correlation IDs for request-response matching over Electron's event-based IPC.

6. **Boundary coercion:** Malli validation runs after camelCase→kebab-case transformation at JS/CLJS boundary.

## Phase 3.3: Streaming to Renderer 🚧

**Goal:** Route ACP session events from Main to Renderer with schema validation.

**Implementation:** `schema.cljs` (discriminated union + transforms), `main/actions.cljs` (IPC forwarding), `preload.js` (listener), `renderer/{core,actions}.cljs`

**Key Learnings:**

1. **⚠️ Malli dispatch runs BEFORE transformers (CRITICAL):**
   Discriminated union (`:multi`) dispatch sees raw input. For JS strings, dispatch must handle both forms:
   ```clojure
   :multi {:dispatch (fn [data]
                       (let [update (or (:session-update data)
                                        (get data "sessionUpdate"))]
                         (keyword update)))}
   ```

2. **IPC channel naming convention:**
   - Main→Renderer broadcasts: colon separator (`acp:session-update`)
   - Renderer→Main requests: slash separator (`acp/new-session`)
   Distinguishes event streams from RPC calls.

3. **Pure Malli transforms:** Eliminated manual camelCase→kebab-case mapping. The `:json-transformer` handles key casing for nested structures automatically.

4. **Schema as contract:** Malli validation at boundary caught dispatch key handling bug immediately.

### Architecture

```
ACP Subprocess
    ↓ agent_message_chunk event
resources/acp/index.js (dispatcher callback)
    ↓ [[:acp.actions/session-update params]]
main/actions.cljs
    ↓ [[:ipc.effects/send-to-renderer "acp:session-update" update]]
preload.js (onAcpSessionUpdate)
    ↓ window.electronAPI.onAcpSessionUpdate(callback)
renderer/core.cljs (coerce via schema)
    ↓ [[:acp.actions/session-update update]]
renderer/actions.cljs (console.log placeholder)
    ↓ [Future: State accumulation + UI]
```

**Test:**
```javascript
const sid = await window.electronAPI.acpNewSession("/Users/paul/Projects/gremllm")
await window.electronAPI.acpPrompt(sid, "Say only: Hello")
// Watch console for streaming events
```

**Remaining work:** Accumulate events in state, display in chat UI, handle all event types (`:agent-thinking`, `:tool-use-chunk`, etc.)

## Resources

- [ACP Protocol Spec](https://agentclientprotocol.org/)
- [ACP SDK on npm](https://www.npmjs.com/package/@agentclientprotocol/sdk)
- [Claude Code ACP on npm](https://www.npmjs.com/package/@zed-industries/claude-code-acp)

## Open Questions

- What tools are available by default?
- Can we restrict tool access (e.g., prevent file writes)?

---

**Last Updated:** 2026-01-26 (Phase 3.3 streaming partial - events flowing to renderer, UI pending)
