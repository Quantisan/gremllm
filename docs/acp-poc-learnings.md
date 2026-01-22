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

## Phase 3: Integration into Gremllm (Future)

**Goals:**
- Implement as ClojureScript effect in `main/effects/acp.cljs`
- Expose IPC channel for renderer to trigger agent runs
- Handle streaming responses back to UI
- Manage subprocess lifecycle (spawn on demand, keep-alive, shutdown)

**Questions to answer:**
- Should subprocess be long-lived or spawn-per-request?
- How to handle concurrent requests?
- What's the latency overhead?
- How to surface tool use to users?

## Resources

- [ACP Protocol Spec](https://agentclientprotocol.org/)
- [ACP SDK on npm](https://www.npmjs.com/package/@agentclientprotocol/sdk)
- [Claude Code ACP on npm](https://www.npmjs.com/package/@zed-industries/claude-code-acp)

## Open Questions

1. What's the cold-start latency for spawning the subprocess?
2. Can we reuse one subprocess for multiple requests?
3. How does the subprocess access our workspace files?
4. What tools are available by default?
5. Can we restrict tool access (e.g., prevent file writes)?

---

**Last Updated:** 2026-01-23 (Phase 2 complete)
