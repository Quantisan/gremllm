# ACP Integration PoC - Learnings

This document captures what we learn as we explore integrating the Agent Client Protocol (ACP) into Gremllm.

## Overview

Agent Client Protocol (ACP) is a JSON-RPC protocol that allows applications to spawn and communicate with Claude Code as an agentic subprocess. The subprocess can use tools to read files, execute commands, and perform other actions on behalf of the application.

**Package:** `@zed-industries/claude-code-acp`
**Protocol Spec:** https://agentclientprotocol.org/
**Implementation Guide**: context/acp_client.md

## Phase 1: Process Lifecycle ✅

**Goal:** Prove we can spawn the ACP subprocess with stdio pipes.

**Implementation:** `test/acp-lifecycle.mjs`

### What Works

```javascript
const proc = spawn("npx", ["@zed-industries/claude-code-acp"], {
  stdio: ["pipe", "pipe", "inherit"],
  env: { ...process.env }
});
```

**Verified:**
- ✅ Process spawns successfully via `npx`
- ✅ `stdin` is writable (`Writable` stream)
- ✅ `stdout` is readable (`Readable` stream)
- ✅ Process responds to `SIGTERM` for clean shutdown
- ✅ Exit handler receives signal information

### Key Learnings

1. **npx auto-installs:** Using `npx @zed-industries/claude-code-acp` automatically handles package installation if not cached. Version `0.13.1` is current.

2. **stdio configuration:** The `["pipe", "pipe", "inherit"]` pattern:
   - `stdin`: pipe for writing JSON-RPC messages
   - `stdout`: pipe for reading JSON-RPC responses
   - `stderr`: inherit to see errors in our console

3. **Environment variables:** The spawned process inherits our environment, including `ANTHROPIC_API_KEY` needed for Claude API calls.

4. **Timing matters:** Need sufficient timeout (5s+) for npx to install package on first run. Subsequent runs are faster.

5. **Exit behavior:** Clean SIGTERM produces `signal=SIGTERM, code=null`. Killing mid-install produces `code=1` npm error (expected).

## Phase 2: NDJSON Protocol ✅

**Goal:** Verify we can establish a valid NDJSON connection with the ACP subprocess using the SDK's `ClientSideConnection` and complete the protocol initialization handshake.

**Implementation:** `test/acp-ndjson.mjs`

### What Works

```javascript
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

// Convert Node streams → Web streams (order matters!)
const input = Writable.toWeb(proc.stdin);   // Where we WRITE to agent
const output = Readable.toWeb(proc.stdout); // Where we READ from agent

// Create NDJSON stream and connection
const stream = acp.ndJsonStream(input, output);
const connection = new acp.ClientSideConnection(() => client, stream);

// Initialize with handshake
const response = await connection.initialize({
  protocolVersion: acp.PROTOCOL_VERSION,
  clientCapabilities: { fs: {}, terminal: false },
  clientInfo: {
    name: "gremllm-test",
    title: "Gremllm ACP Test",
    version: "0.1.0"
  }
});
```

**Verified:**
- ✅ SDK's `ndJsonStream` correctly parses NDJSON framing
- ✅ `ClientSideConnection` handles JSON-RPC protocol
- ✅ `initialize()` completes handshake successfully
- ✅ Protocol version negotiation works (returns `1`)
- ✅ Agent capabilities are returned in structured format

### Key Learnings

1. **Stream conversion order matters:** The naming is from the SDK's perspective:
   - `input` = `Writable.toWeb(proc.stdin)` - where SDK writes TO the agent
   - `output` = `Readable.toWeb(proc.stdout)` - where SDK reads FROM the agent
   - Getting these backwards causes `getWriter is not a function` errors

2. **Agent capabilities returned:** The `@zed-industries/claude-code-acp` agent reports:
   ```json
   {
     "promptCapabilities": {
       "image": true,
       "embeddedContext": true
     },
     "mcpCapabilities": {
       "http": true,
       "sse": true
     },
     "sessionCapabilities": {
       "fork": {},
       "resume": {}
     }
   }
   ```
   - Supports image prompts
   - Supports embedded context in prompts
   - Can use HTTP and SSE MCP servers
   - Can fork and resume sessions

3. **Minimal client interface:** Only two methods required for handshake:
   - `requestPermission(request)` - Handle permission requests from agent
   - `sessionUpdate(update)` - Receive streaming progress notifications
   - File system and terminal methods are optional (check capabilities first)

4. **Clean initialization:** The `initialize()` call is straightforward—just pass protocol version, capabilities, and client info. No complex setup needed.

5. **Protocol version:** Agent returns `protocolVersion: 1`, matching the SDK's `PROTOCOL_VERSION` constant.

### Gotchas

- **Initial error:** Attempted manual Web stream conversion was complex and error-prone. Node's built-in `Writable.toWeb()` and `Readable.toWeb()` work perfectly.
- **Stream direction confusion:** It's easy to swap input/output since they're relative to different perspectives (SDK vs subprocess).

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

## Phase 3.1: SDK Wrapper Learnings ✅

**Goal:** Wrap the ACP SDK in a JS module callable from CLJS, with event dispatch bridge.

**Implementation:** `resources/acp.js`, `src/gremllm/main/effects/acp.cljs`

### What Works

```javascript
// resources/acp.js exports:
initialize()              // Spawn subprocess, complete handshake
newSession(cwd)           // Create session, return sessionId
prompt(sessionId, text)   // Send prompt, return full result object
shutdown()                // Clean process termination
setDispatcher(fn)         // Wire JS callbacks → CLJS dispatch
```

**Verified:**
- ✅ Subprocess lifecycle management (spawn, shutdown)
- ✅ Session creation with working directory
- ✅ Prompt/response round-trip
- ✅ Dispatch bridge routes `sessionUpdate` events to callback
- ✅ Permission auto-approval with logging
- ✅ Idempotent initialization

### Key Learnings

1. **Shadow-CLJS module resolution:** Place JS module in `resources/acp.js` and configure:
   ```clojure
   :js-options {:resolve {"acp" {:target :file :file "resources/acp.js"}}}
   ```
   Then import as `["acp" :as acp-js]` in CLJS.

2. **Dispatch bridge pattern:** JS client callbacks can't directly call CLJS. Solution:
   ```javascript
   // JS side
   let dispatcher = null;
   export function setDispatcher(fn) { dispatcher = fn; }

   const client = {
     async sessionUpdate(params) {
       if (dispatcher) dispatcher("acp.events/session-update", params);
     }
   };
   ```
   CLJS wires this during initialization to route events into Nexus dispatch.

3. **Session management required:** The ACP protocol requires sessions. Original spec had `prompt(text)` but actual API needs `prompt(sessionId, text)`. Sessions are scoped to a working directory.

4. **Permission handling:** For Slice 1, auto-approve all permissions:
   ```javascript
   async requestPermission(params) {
     const firstOption = params.options?.[0];
     return { outcome: { outcome: "selected", optionId: firstOption?.optionId ?? 0 } };
   }
   ```

5. **Key casing at boundary:** JS sends camelCase (`sessionId`), CLJS expects kebab-case (`:session-id`). Need coercion function at boundary—discovered during code review.

### Test

```bash
node test/acp-dispatch.mjs
# Exercises: init → session → prompt → verify events → shutdown
```

## Phase 3.2: IPC Wiring Learnings ✅

**Goal:** Connect renderer to ACP agent via IPC so DevTools can call `window.electronAPI.acpNewSession()` and `acpPrompt()`.

**Implementation:**
- `resources/public/js/preload.js` - Preload API
- `src/gremllm/main/core.cljs` - IPC handlers + dispatcher wiring
- `src/gremllm/main/actions.cljs` - Action/effect registrations
- `src/gremllm/schema.cljs` - Boundary coercion
- `resources/acp/` - Local npm package

### What Works

```javascript
// In DevTools console:
const sessionId = await window.electronAPI.acpNewSession("/path/to/workspace")
// → "a9e59303-732c-4b48-8a7c-e942c8b8b96f"

const result = await window.electronAPI.acpPrompt(sessionId, "Say only: Hello")
// → {stopReason: 'end_turn'}

// Meanwhile, terminal shows streaming session updates:
// [ACP] Session update: a9e59303... {content: {text: 'Hello'}, sessionUpdate: 'agent_message_chunk'}
```

**Verified:**
- ✅ Preload exposes `acpNewSession(cwd)` and `acpPrompt(sessionId, text)`
- ✅ IPC handlers dispatch to Nexus actions
- ✅ Effects call through to acp.js module
- ✅ Dispatcher bridge routes session events back to Nexus
- ✅ Schema coercion handles camelCase→kebab-case at boundary
- ✅ Lazy initialization spawns subprocess on first use

### Key Learnings

1. **Shadow-cljs watch mode requires npm resolution:** The `:js-options {:resolve {...}}` config only works for bundled builds. In watch/dev mode, shadow-cljs creates shims that use Node's `require()` at runtime. Solution: structure local JS as an npm package.

2. **Local npm package pattern:**
   ```
   resources/acp/
   ├── package.json   # {"name": "acp", "main": "index.js"}
   └── index.js       # The actual module code
   ```
   Then in package.json: `"acp": "file:./resources/acp"`

   npm creates a symlink, so file changes are immediately available without reinstall.

3. **CommonJS required for dev mode:** ES modules (`import`/`export`) cause shim errors in shadow-cljs watch mode. Use CommonJS (`require`/`module.exports`) for local modules consumed by the main process.

4. **Lazy initialization:** Rather than requiring explicit `initialize()` calls, `newSession()` now auto-initializes on first use:
   ```javascript
   async function newSession(cwd) {
     if (!connection) await initialize(); // Lazy init
     // ...
   }
   ```

5. **Streaming vs return values:** ACP responses stream via `sessionUpdate` events, not the `prompt()` return value. The return is just `{stopReason: 'end_turn'}` signaling completion. Actual content arrives as `agent_message_chunk` events through the dispatcher bridge.

6. **IPC correlation pattern:** The `createIPCBoundary` helper in preload.js generates unique correlation IDs for request-response matching over Electron's event-based IPC. Main process handlers receive this ID and use it for reply channels.

7. **Boundary coercion with Malli validation:** Schema coercion functions now validate with Malli after transformation:
   ```clojure
   (defn session-update-from-js [js-data]
     (as-> js-data $
       (js->clj $ :keywordize-keys true)
       {:session-id (:sessionId $)
        :update (:update $)}
       (m/coerce SessionUpdate $ mt/json-transformer)))
   ```

### Architecture Summary

```
Renderer (DevTools)
    │
    ▼ window.electronAPI.acpNewSession(cwd)
Preload (createIPCBoundary)
    │
    ▼ ipcRenderer.send("acp/new-session", correlationId, cwd)
Main Process (core.cljs)
    │
    ▼ nxr/dispatch [[:acp.effects/new-session cwd]]
Effects (actions.cljs)
    │
    ▼ acp-effects/new-session → promise
    │
    ▼ dispatch [:ipc.effects/promise->reply promise]
IPC Effects
    │
    ▼ ipcMain.send("acp/new-session-success-{id}", sessionId)
Preload
    │
    ▼ resolve(sessionId)
Renderer
    ▼ returns session ID
```

### Gotchas

- **`process` not in renderer:** Can't use `process.cwd()` in DevTools—Electron's context isolation. Pass explicit paths.
- **Module not found in dev:** If you see "Cannot find module 'acp'" in dev mode, the local package isn't linked. Run `npm install` to create the symlink.
- **Dispatcher must be wired at startup:** The `set-dispatcher!` call in `setup-system-resources` ensures events flow before any sessions are created.

## Phase 3.3: Streaming to Renderer (Partial) 🚧

**Goal:** Route ACP session update events from Main process to Renderer process with full schema validation.

**Implementation:**
- `src/gremllm/schema.cljs:348-417` - `AcpUpdate` discriminated union, `AcpSessionUpdate`, pure Malli transforms
- `src/gremllm/main/actions.cljs:137-140` - Forward events via `acp:session-update` IPC channel
- `resources/public/js/preload.js:47` - `onAcpSessionUpdate` listener registration
- `src/gremllm/renderer/core.cljs:48-50` - Wire listener, dispatch with schema coercion
- `src/gremllm/renderer/actions.cljs:184-188` - Console.log placeholder action

### What Works

```bash
# In DevTools console:
const sessionId = await window.electronAPI.acpNewSession("/Users/paul/Projects/gremllm")
const result = await window.electronAPI.acpPrompt(sessionId, "Say only: Hello")

# Renderer console shows coerced, validated events:
# {:session-id "abc...", :update {:session-update :agent-message-chunk, :content {:text "Hello"}}}
```

**Verified:**
- ✅ Events flow from ACP subprocess → Main → Renderer via IPC
- ✅ Schema discriminates on `:session-update` field (`:agent-message-chunk`, `:usage`, etc.)
- ✅ Malli dispatch function handles raw keys BEFORE transforms
- ✅ Pure Malli transformers (no manual key mapping)
- ✅ Boundary coercion validates against schema

### Key Learnings

1. **Malli dispatch runs BEFORE transformers:** When using discriminated unions (`:multi` schema), the dispatch function sees raw input data. For keys coming from JS as strings, dispatch must handle both string and keyword forms:
   ```clojure
   :multi {:dispatch (fn [data]
                       (let [update (or (:session-update data)
                                        (get data "sessionUpdate"))]
                         (keyword update)))}
   ```

2. **IPC channel naming convention:** Main→Renderer broadcast channels use colon separator (`acp:session-update`), while Renderer→Main request channels use slash (`acp/new-session`). This distinguishes event streams from RPC-style calls.

3. **Pure Malli transforms > manual coercion:** Earlier phases manually mapped camelCase→kebab-case. Phase 3.3 uses pure Malli transformers for nested structures:
   ```clojure
   [:map
    [:session-update [:enum :agent-message-chunk :usage ...]]
    [:content {:optional true} [:map [:text string?]]]]
   ```
   The `:json-transformer` handles key casing automatically.

4. **Schema validation catches drift:** Adding Malli validation to boundary coercion caught the dispatch key handling bug immediately. The schema acts as a contract between processes.

5. **Event flow is one-way:** Main process broadcasts session updates to all renderer windows. No reply needed—these are notifications, not requests.

### Architecture

```
ACP Subprocess (Node)
    │ agent_message_chunk event
    ▼
resources/acp/index.js (dispatcher)
    │ Dispatch bridge callback
    ▼
main/core.cljs (dispatcher handler)
    │ [[:acp.actions/session-update params]]
    ▼
main/actions.cljs (action)
    │ [[:ipc.effects/send-to-renderer "acp:session-update" update]]
    ▼
preload.js (onAcpSessionUpdate)
    │ window.electronAPI.onAcpSessionUpdate(callback)
    ▼
renderer/core.cljs (setup-acp-listener)
    │ Coerce via schema.cljs/acp-update-from-js
    ▼
renderer/actions.cljs (acp.actions/session-update)
    │ console.log placeholder
    ▼
[Future: State accumulation + UI display]
```

### What's Next

**Remaining work for Slice 2:**
- Accumulate events in Renderer state (append text chunks, track usage)
- Display streaming responses in chat UI
- Handle all event types (`:agent-thinking`, `:tool-use-chunk`, etc.)

**Test Command:**
```javascript
// DevTools console
const sid = await window.electronAPI.acpNewSession("/Users/paul/Projects/gremllm")
await window.electronAPI.acpPrompt(sid, "Say only: Hello")
// Watch console.log output for streaming events
```

## Resources

- [ACP Protocol Spec](https://agentclientprotocol.org/)
- [ACP SDK on npm](https://www.npmjs.com/package/@agentclientprotocol/sdk)
- [Claude Code ACP on npm](https://www.npmjs.com/package/@zed-industries/claude-code-acp)

## Open Questions

1. ~~What's the cold-start latency for spawning the subprocess?~~ **Answered:** 3-5s on first run (npx install), ~1s thereafter.
2. ~~Can we reuse one subprocess for multiple requests?~~ **Answered:** Yes, one subprocess supports multiple sessions.
3. ~~How does the subprocess access our workspace files?~~ **Answered:** Session is scoped to a `cwd` passed to `newSession()`.
4. What tools are available by default?
5. Can we restrict tool access (e.g., prevent file writes)?

---

**Last Updated:** 2026-01-26 (Phase 3.3 streaming partial - events flowing to renderer, UI pending)
