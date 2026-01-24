# ACP Integration - Slice 1: SDK Wrapper + Dispatch Bridge

**Date:** 2026-01-23
**Status:** Complete ✅

## Goal

Prove the JS/SDK layer works: subprocess lifecycle, session management, prompt/response, and event dispatch bridge.

IPC wiring (renderer → main) is deferred to Slice 1b.

## Files

| File | Action | Purpose | Status |
|------|--------|---------|--------|
| `resources/acp.js` | Create | JS module wrapping ACP SDK | ✅ Done |
| `src/gremllm/main/effects/acp.cljs` | Create | CLJS effects calling JS module | ✅ Done |
| `src/gremllm/main/actions.cljs` | Edit | Register ACP effects and event actions | ✅ Done |
| `shadow-cljs.edn` | Edit | Add resolve alias for `"acp"` module | ✅ Done |
| `test/acp-dispatch.mjs` | Create | Test script for verification | ✅ Done |

**Deferred to Slice 1b:**
| File | Action | Purpose |
|------|--------|---------|
| `src/gremllm/main/core.cljs` | Edit | Add IPC handler `acp/prompt` |
| `resources/public/js/preload.js` | Edit | Add `sendAcpPrompt` API |

## Implementation Steps

### Step 1: Create JS Interop Module ✅

**Location:** `resources/acp.js` (resolved via Shadow-CLJS `:js-options`)

The JS module manages subprocess lifecycle and exposes a clean API for CLJS.

```javascript
// Actual exports:
// - initialize() → Promise<void>     - Spawn subprocess, complete handshake
// - newSession(cwd) → Promise<string> - Create session, return sessionId
// - prompt(sessionId, text) → Promise<result> - Send prompt, return full result
// - shutdown() → void                 - Clean process termination
// - setDispatcher(fn) → void          - Wire event callbacks to CLJS dispatch
```

**Key responsibilities:**
- Spawn `npx @zed-industries/claude-code-acp` subprocess
- Convert Node streams → Web streams for SDK
- Create `ClientSideConnection` with client callbacks
- Handle initialization handshake
- **Dispatch bridge:** Route `sessionUpdate` events to CLJS via `setDispatcher()`
- Auto-approve permissions (Slice 1 simplification)

**Why JS instead of CLJS?**
- ES module imports (`import * as acp from "@agentclientprotocol/sdk"`)
- Node stream APIs
- ClojureScript interop with ES modules is tricky; JS wrapper isolates complexity

**Why `resources/acp.js` instead of `src/`?**
Shadow-CLJS resolves it via `:js-options {:resolve {"acp" {:target :file :file "resources/acp.js"}}}`. This keeps the module importable as `["acp" :as acp-js]` in CLJS.

### Step 2: Create CLJS Effects ✅

**Location:** `src/gremllm/main/effects/acp.cljs`

Thin wrapper calling JS module.

```clojure
(ns gremllm.main.effects.acp
  (:require ["acp" :as acp-js]))

;; Exports:
;; - initialize      - Spawn subprocess, complete handshake
;; - new-session     - Create session for working directory
;; - prompt          - Send prompt, return promise of result
;; - shutdown        - Clean process termination
;; - set-dispatcher! - Wire event callbacks to CLJS dispatch
```

### Step 3: Register Effects and Event Actions ✅

**Location:** `src/gremllm/main/actions.cljs`

```clojure
;; Effects
(nxr/register-effect! :acp.effects/initialize ...)
(nxr/register-effect! :acp.effects/prompt ...)

;; Event action (receives dispatched events from JS)
(nxr/register-action! :acp.events/session-update ...)
```

### Step 4: Add IPC Handler ⏳ (Deferred to Slice 1b)

Pattern: async handler (like `chat/send-message`).

```clojure
(.on ipcMain "acp/prompt"
     (fn [event ipc-correlation-id session-id text]
       ;; Dispatch to action that triggers effect
       ;; Response flows back via ipc.effects/reply
       ))
```

### Step 5: Integration Test ✅

**Location:** `test/acp-dispatch.mjs`

Standalone Node.js test exercising the JS module + dispatch bridge:
1. Wire up test dispatcher to collect events
2. Initialize connection
3. Create session
4. Send prompt, verify response
5. Verify `session-update` events were dispatched
6. Clean shutdown

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Renderer Process                                                        │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ window.electronAPI.sendAcpPrompt("Hello")                           │ │
│ │                         ↓                                           │ │
│ │ preload.js → ipcRenderer.send("acp/prompt", correlationId, text)    │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────│────────────────────────────────────────┘
                                 │ IPC
                                 ↓
┌─────────────────────────────────────────────────────────────────────────┐
│ Main Process                                                            │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ ipcMain.on("acp/prompt") → nxr/dispatch [:acp.effects/prompt]       │ │
│ │                                    ↓                                │ │
│ │ :acp.effects/prompt → acp.mjs.prompt(text)                          │ │
│ │                                    ↓                                │ │
│ │                     connection.prompt({sessionId, prompt: [...]})   │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────│────────────────────────────────────────┘
                                 │ stdio (NDJSON)
                                 ↓
┌─────────────────────────────────────────────────────────────────────────┐
│ ACP Agent Subprocess (@zed-industries/claude-code-acp)                  │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ Receives JSON-RPC → Calls Claude API → Uses tools → Returns result  │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

## Verification

**Test 1: JS module + dispatch bridge ✅**
```bash
node test/acp-dispatch.mjs
# Verifies: initialize, newSession, prompt, event dispatch, shutdown
```

**Test 2: IPC from renderer console ⏳ (Slice 1b)**
```javascript
// In Electron DevTools console:
await window.electronAPI.sendAcpPrompt(sessionId, "Hello")
// Requires IPC handler + preload API (deferred)
```

## Out of Scope (Slice 1)

- IPC wiring (renderer → main) - **Slice 1b**
- Wiring dispatcher during app initialization - **Slice 1b**
- Streaming updates to renderer (Slice 2)
- Topic integration (Slice 3)
- Renderer UI (Slice 4)
- Permission request UI (auto-approve for now)
- Error recovery / reconnection
- Concurrent request handling

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| ES module interop issues | JS wrapper isolates complexity |
| Cold start latency | Accept 3-5s first-time cost; long-lived process amortizes |
| Subprocess crash | For Slice 1: log error, return failure. Reconnect logic in later slice |

## Next Steps

- **Slice 1b:** IPC wiring (renderer → main → ACP)
  - Add IPC handler in `core.cljs`
  - Add `sendAcpPrompt` to preload.js
  - Wire dispatcher during app initialization
  - Fix key casing at JS/CLJS boundary (camelCase → kebab-case)
- **Slice 2:** Streaming sessionUpdate events to renderer
- **Slice 3:** Session lifecycle management (per-topic)
- **Slice 4:** Renderer UI integration

## Known Issues

1. **Key casing mismatch:** JS dispatches `{ sessionId: ... }` but CLJS expects `:session-id`. Need coercion at boundary before Slice 1b.
