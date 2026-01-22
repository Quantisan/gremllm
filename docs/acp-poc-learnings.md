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

## Phase 2: NDJSON Protocol (Next)

**Goals:**
- Wire up NDJSON streams using `@agentclientprotocol/sdk`
- Send `initialize` request
- Receive and parse `initialize` response
- Verify protocol handshake completes

**Questions to answer:**
- How does the SDK handle stream parsing?
- What's in the `initialize` request payload?
- What capabilities are returned in response?
- How do we handle protocol errors?

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

**Last Updated:** 2026-01-22 (Phase 1 complete)
