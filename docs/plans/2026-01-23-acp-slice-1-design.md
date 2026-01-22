# ACP Integration - Slice 1: End-to-End Prompt/Response

**Date:** 2026-01-23
**Status:** In Progress

## Goal

Prove the complete data flow works: renderer → main → ACP agent → response → renderer.

No streaming, no session management, no UI. Just the plumbing.

## Files

| File | Action | Purpose |
|------|--------|---------|
| `src/gremllm/main/acp.mjs` | Create | JS module wrapping ACP SDK |
| `src/gremllm/main/effects/acp.cljs` | Create | CLJS effects calling JS module |
| `src/gremllm/main/actions.cljs` | Edit | Register ACP effects |
| `src/gremllm/main/core.cljs` | Edit | Add IPC handler `acp/prompt` |
| `test/acp-integration.mjs` | Create | Test script for verification |

## Implementation Steps

### Step 1: Create JS Interop Module (`acp.mjs`)

The JS module manages subprocess lifecycle and exposes a clean API for CLJS.

```javascript
// Exports:
// - initialize() → Promise<void>  - Spawn subprocess, complete handshake
// - prompt(text) → Promise<string> - Send prompt, return result text
// - shutdown() → void             - Clean process termination
```

**Key responsibilities:**
- Spawn `npx @zed-industries/claude-code-acp` subprocess
- Convert Node streams → Web streams for SDK
- Create `ClientSideConnection` with minimal client
- Handle initialization handshake
- Track connection state (not-started → initializing → ready → closed)

**Why JS instead of CLJS?**
- ES module imports (`import * as acp from "@agentclientprotocol/sdk"`)
- Node stream APIs
- ClojureScript interop with ES modules is tricky; JS wrapper isolates complexity

### Step 2: Create CLJS Effects (`effects/acp.cljs`)

Thin wrapper calling JS module.

```clojure
(ns gremllm.main.effects.acp
  (:require ["./acp.mjs" :as acp-js]))

;; Effects:
;; - :acp.effects/prompt - Send prompt, return promise of response
;; - :acp.effects/initialize - Ensure connection ready (lazy init)
```

### Step 3: Register Effects (`actions.cljs`)

Add to effect registry:

```clojure
(nxr/register-effect! :acp.effects/prompt acp-effects/prompt)
```

### Step 4: Add IPC Handler (`core.cljs`)

Pattern: async handler (like `chat/send-message`).

```clojure
(.on ipcMain "acp/prompt"
     (fn [event ipc-correlation-id text]
       ;; Dispatch to action that triggers effect
       ;; Response flows back via ipc.effects/reply
       ))
```

### Step 5: Module Integration Test (`test/acp-integration.mjs`)

Standalone Node.js test focused on the JS interop layer (no IPC):
1. Imports and calls `acp.mjs` directly
2. Sends a simple prompt ("Hello, respond with just 'Hi'")
3. Verifies response received
4. Cleans up subprocess

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
│ │ ipcMain.on("acp/prompt") → nxr/dispatch [:acp.actions/prompt]       │ │
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

**Test 1: JS module standalone**
```bash
node test/acp-integration.mjs
# Expected: Sends prompt, prints response, exits cleanly
```

**Test 2: IPC from renderer console**
```javascript
// In Electron DevTools console:
await window.electronAPI.sendAcpPrompt("Hello, respond with just 'Hi'")
// Expected: Returns "Hi" (or similar short response)
```

## Out of Scope (Slice 1)

- Streaming updates (Slice 2)
- Session management (Slice 3)
- Topic integration (Slice 3)
- Renderer UI (Slice 4)
- Permission request handling (deferred)
- Error recovery / reconnection
- Concurrent request handling

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| ES module interop issues | JS wrapper isolates complexity |
| Cold start latency | Accept 3-5s first-time cost; long-lived process amortizes |
| Subprocess crash | For Slice 1: log error, return failure. Reconnect logic in later slice |

## Next Steps After Slice 1

- **Slice 2:** Add streaming sessionUpdate events
- **Slice 3:** Session lifecycle management (per-topic)
- **Slice 4:** Renderer UI integration
