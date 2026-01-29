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
| **2** | Chat UI integration | ✅ Complete |
| **3** | Persist ACP responses to messages | Planned |
| **4** | Session lifecycle (per-topic) | Planned |

### Slice Dependencies

Slice 1 (SDK) → Slice 1b (IPC) → Slice 2 (streaming) + Slice 3 (sessions) → Slice 4 (UI)

### Implementation Plans

- **Slice 1:** `docs/plans/2026-01-23-acp-slice-1-design.md`
- **Slice 2:** `docs/plans/2026-01-27-acp-slice-2-chat-ui.md`
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

## Phase 3.3: Chat UI Integration ✅

**Goal:** Route ACP session events to renderer, accumulate chunks in state, and display streaming responses in chat UI.

**Implementation:**
- Main: `main/core.cljs` (workspace-dir resolution), `main/actions.cljs` (IPC forwarding)
- Renderer: `renderer/state/acp.cljs`, `renderer/actions/acp.cljs`, `renderer/actions/ui.cljs`, `renderer/ui/chat.cljs`
- Boundary: `preload.js` (IPC listener), `schema.cljs` (validation)

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

5. **Discriminator value normalization:** Session-update type strings (`"agent_message_chunk"`) are converted to kebab-case keywords (`:agent-message-chunk`) for internal consistency. This happens via a custom Malli transformer that runs after key transformation but before schema validation.

6. **State accumulation pattern:** Chunks accumulate in flat state path (`[:acp :chunks]`), enabling incremental assembly of streaming responses. Loading indicator (`[:acp :loading?]`) shows between submit and first chunk arrival.

7. **Session lifecycle:** ACP session initializes automatically on workspace load. Main process resolves workspace directory from its own state, eliminating renderer→main parameter passing.

8. **Form routing:** User messages now route through `acp.actions/send-prompt` instead of direct LLM effects. ACP responses stream back as chunks and render as growing assistant message.

### Data Flow

```
User submit form
    ↓ [:form.actions/submit]
renderer/actions/ui.cljs
    ↓ [:acp.actions/send-prompt text]
renderer/actions/acp.cljs
    ↓ IPC: acpPrompt(session-id, text)
main/core.cljs
    ↓ resources/acp/index.js (ACP SDK)
    ↓ agent_message_chunk events
    ↓ dispatcher callback
    ↓ [[:acp.actions/session-update params]]
main/actions.cljs
    ↓ [[:ipc.effects/send-to-renderer "acp:session-update" update]]
preload.js (onAcpSessionUpdate)
    ↓ window.electronAPI.onAcpSessionUpdate(callback)
renderer/core.cljs (schema coercion)
    ↓ [[:acp.events/session-update update]]
renderer/actions.cljs (chunk handler)
    ↓ State: [:acp :chunks] accumulates text
    ↓        [:acp :loading?] set to false
renderer/ui/chat.cljs
    ↓ Renders streaming assistant message
```

**Current State:**
- Form submissions route through ACP
- Streaming chunks accumulate in flat state structure
- UI displays growing assistant message with loading indicator
- Session initializes automatically on workspace load

**Next Steps (Slice 3):**
- Interleave ACP chunks chronologically with user messages for display and persistence
- Design unified message data structure that handles both user messages and ACP responses
- Persist complete conversations (user + assistant) to topic files
- Handle conversation history context for subsequent prompts

## Session Persistence Investigation

**Goal:** Determine if ACP sessions persist across connection restarts (process crashes, app restarts).

**Key Learnings:**

1. **✅ Sessions DO persist across connection restarts:**
   - ACP agent maintains session state independently of client connection
   - After killing and restarting the agent process, sessions can be resumed using stored sessionId
   - Conversation history is preserved—agent remembers previous context

2. **⚠️ Check `sessionCapabilities.resume`, NOT `loadSession`:**
   - Claude Code ACP advertises: `agentCapabilities.sessionCapabilities.resume: {}`
   - Does NOT advertise: `agentCapabilities.loadSession: true`
   - This is the modern ACP approach (as of protocol updates in late 2025/early 2026)

3. **⚠️ CRITICAL: Claude Code ACP does NOT support loading conversation history:**
   - ACP protocol spec defines `session/load` to stream full conversation history via `sessionUpdate` notifications
   - Claude Code ACP only implements `unstable_resumeSession()` - restores agent's internal context WITHOUT replaying message history
   - Calling `connection.loadSession()` returns error: `"Method not found": session/load`
   - After resume: agent remembers context, but client receives **zero historical messages**
   - Client UI remains empty—must persist and restore messages separately

4. **Why `loadSession` isn't available:**
   - Claude Agent SDK doesn't expose API to retrieve historical messages (as of 2026-01-29)
   - SDK stores transcripts in `~/.claude/projects/{project-slug}/{session-id}.jsonl` but no programmatic access
   - Technical challenges: ACP session IDs (UUIDv7) differ from Claude's internal session IDs (UUIDv4)

5. **Workaround options:**
   - **Option A (recommended):** Store messages in Gremllm topic files, use `unstable_resumeSession()` for agent context only
   - **Option B (fragile):** Parse Claude's JSONL transcripts manually
     - Path: `~/.claude/projects/{cwd.replace(/\//g, '-')}/{session-id}.jsonl`
     - Undocumented format, may break between versions
     - Not recommended for production

6. **Implementation pattern for resume (context only, no history):**
   ```javascript
   // Check capability during initialization
   const supportsResume = initResult.agentCapabilities?.sessionCapabilities?.resume !== undefined;

   // On reconnect, resume agent context (no message history)
   if (supportsResume && hasStoredSessionId) {
     await connection.unstable_resumeSession({
       sessionId: storedSessionId,
       cwd: workspaceDir,
       mcpServers: []
     });
   }
   ```

7. **Storage implications for Gremllm:**
   - Must persist messages ourselves in topic files for UI display
   - Can use `unstable_resumeSession()` to restore agent's memory across restarts
   - Agent remembers previous conversation context for continuity
   - But we still need our own message storage to populate the UI

8. **⚠️ MONITOR THESE ISSUES:**
   - [anthropics/claude-agent-sdk-typescript#14](https://github.com/anthropics/claude-agent-sdk-typescript/issues/14) - "Feature Request: API to retrieve historical messages" (OPEN)
   - [zed-industries/claude-code-acp#64](https://github.com/zed-industries/claude-code-acp/issues/64) - "Feature Request: Add loadSession support" (OPEN)
   - If implemented, could eliminate need for custom message persistence in Gremllm
   - As of 2026-01-29: both issues open, no implementation timeline

**Test:** `test/acp-session-history.mjs` verifies session resumption across process restarts.

**References:**
- [ACP SDK ClientSideConnection](https://agentclientprotocol.github.io/typescript-sdk/classes/ClientSideConnection.html)
- [ACP Protocol Schema](https://agentclientprotocol.com/protocol/schema)

## Resources

- [ACP Protocol Spec](https://agentclientprotocol.org/)
- [ACP SDK on npm](https://www.npmjs.com/package/@agentclientprotocol/sdk)
- [Claude Code ACP on npm](https://www.npmjs.com/package/@zed-industries/claude-code-acp)

## Open Questions

- What tools are available by default?
- Can we restrict tool access (e.g., prevent file writes)?

---

**Last Updated:** 2026-01-29 (Session persistence investigation complete)
