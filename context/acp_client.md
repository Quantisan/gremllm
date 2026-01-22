# ACP Client Implementation Guide

The Agent Client Protocol (ACP) enables standardized communication between code editors and AI agents. This guide covers implementing the client side—what Gremllm needs to connect to and orchestrate AI agents.

**References:**
- [Protocol Specification](https://agentclientprotocol.com/protocol/overview)
- [TypeScript SDK](https://github.com/agentclientprotocol/typescript-sdk)
- [SDK API Docs](https://agentclientprotocol.github.io/typescript-sdk)

## Core Concepts

**Agents** are programs that use generative AI to autonomously modify code. They run as client subprocesses.

**Clients** (Gremllm) provide the interface between users and agents—managing environments, user interactions, and resource access control.

**Protocol**: JSON-RPC 2.0 with two message patterns:
- **Methods**: Request-response pairs expecting results or errors
- **Notifications**: Unidirectional messages requiring no response

## TypeScript SDK

```bash
npm install @agentclientprotocol/sdk
```

Key exports:
- `ClientSideConnection` - Main class for building clients
- `ndJsonStream` - Creates bidirectional stream from stdio
- `PROTOCOL_VERSION` - Current protocol version constant

## Agent Implementation

We use [claude-code-acp](https://github.com/zed-industries/claude-code-acp) as our ACP agent.

**Installation:**
```bash
npm install -g @zed-industries/claude-code-acp
```

**Spawning the agent:**
```typescript
const agentProcess = spawn("claude-code-acp", [], {
  stdio: ["pipe", "pipe", "inherit"],
  env: { ...process.env, ANTHROPIC_API_KEY: apiKey }
});
```

The agent provides Claude Code capabilities including tool execution, terminal access, and slash commands. Specific capabilities are discovered through the initialization handshake.

## Connection Lifecycle

### 1. Spawn Agent and Create Connection

Agents communicate over stdio with NDJSON framing (newline-delimited JSON).

```typescript
import { spawn } from "node:child_process";
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

// Spawn agent as subprocess
const agentProcess = spawn("npx", ["tsx", "agent.ts"], {
  stdio: ["pipe", "pipe", "inherit"]  // stdin, stdout, stderr
});

// Convert Node streams to Web streams
const input = Writable.toWeb(agentProcess.stdin!);
const output = Readable.toWeb(agentProcess.stdout!) as ReadableStream<Uint8Array>;

// Create ACP stream and connection
const client = new MyClient();
const stream = acp.ndJsonStream(input, output);
const connection = new acp.ClientSideConnection((_agent) => client, stream);
```

**Stream rules:**
- Messages are newline-delimited and MUST NOT contain embedded newlines
- Agent reads JSON-RPC from stdin, writes responses to stdout
- Agent MAY write UTF-8 to stderr for logging (client may capture or ignore)

### 2. Initialize

Negotiate protocol version and exchange capabilities.

```typescript
const initResult = await connection.initialize({
  protocolVersion: acp.PROTOCOL_VERSION,
  clientCapabilities: {
    fs: {
      readTextFile: true,
      writeTextFile: true
    },
    terminal: true
  },
  clientInfo: {
    name: "gremllm",
    title: "Gremllm",
    version: "1.0.0"
  }
});

// Check response
console.log(`Connected to agent (protocol v${initResult.protocolVersion})`);
console.log(`Agent capabilities:`, initResult.agentCapabilities);
```

**Capability negotiation:**
- All omitted capabilities are treated as unsupported
- If versions don't match, client should close the connection
- Check `agentCapabilities.loadSession` before attempting session resumption

### 3. Create Session

Sessions provide isolated conversation contexts.

```typescript
const sessionResult = await connection.newSession({
  cwd: "/path/to/workspace",  // Absolute path, file system boundary
  mcpServers: []              // MCP server configurations
});

const sessionId = sessionResult.sessionId;
```

**MCP Server transports (if needed):**
- Stdio: All agents must support
- HTTP: When `agentCapabilities.mcp.http` is true
- SSE: Deprecated, when `agentCapabilities.mcp.sse` is true

### 4. Send Prompts

```typescript
const promptResult = await connection.prompt({
  sessionId: sessionId,
  prompt: [
    { type: "text", text: "Hello, agent!" }
  ]
});

console.log(`Completed with: ${promptResult.stopReason}`);
```

**Content types** (check `promptCapabilities` during init):
- `text` - Always supported, may include markdown
- `image` - Requires `promptCapabilities.image`
- `audio` - Requires `promptCapabilities.audio`
- `resource_link` - Always supported (baseline)

**Stop reasons:**
- `end_turn` - Model finished normally
- `max_tokens` - Token limit reached
- `max_turn_requests` - Maximum model requests exceeded
- `refusal` - Agent declined to continue
- `cancelled` - Client cancelled via `session/cancel`

### 5. Handle Session Updates

The agent sends real-time progress via `session/update` notifications.

```typescript
class MyClient implements acp.Client {
  async sessionUpdate(params: acp.SessionNotification): Promise<void> {
    const update = params.update;

    switch (update.sessionUpdate) {
      case "agent_message_chunk":
        // Streaming text from the model
        if (update.content.type === "text") {
          process.stdout.write(update.content.text);
        }
        break;

      case "tool_call":
        // New tool invocation started
        console.log(`Tool: ${update.title} (${update.status})`);
        // status: pending, in_progress, completed, failed
        break;

      case "tool_call_update":
        // Progress on existing tool
        console.log(`Tool ${update.toolCallId}: ${update.status}`);
        break;

      case "plan":
        // Agent's execution strategy
        console.log("Plan received:", update);
        break;

      case "agent_thought_chunk":
        // Internal reasoning (if exposed)
        break;

      case "user_message_chunk":
        // Echoed user input (during session load)
        break;
    }
  }
}
```

### 6. Handle Permission Requests

Agents request approval for sensitive operations.

```typescript
class MyClient implements acp.Client {
  async requestPermission(
    params: acp.RequestPermissionRequest
  ): Promise<acp.RequestPermissionResponse> {
    console.log(`Permission: ${params.toolCall.title}`);

    // Present options to user
    params.options.forEach((opt, i) => {
      // opt.kind: allow_once, allow_always, reject_once, reject_always
      console.log(`${i + 1}. ${opt.name} (${opt.kind})`);
    });

    // Get user choice...
    const selectedOption = params.options[userChoice];

    return {
      outcome: {
        outcome: "selected",
        optionId: selectedOption.optionId
      }
    };

    // Or if user cancelled:
    // return { outcome: { outcome: "cancelled" } };
  }
}
```

**Tool call structure:**
- `toolCallId` - Unique within session
- `title` - Human-readable description
- `kind` - Category: read, edit, delete, move, search, execute, think, fetch
- `status` - Execution state: pending, in_progress, completed, failed
- `content` - Results (text, images, resources, diffs, terminals)
- `locations` - Affected files with paths and optional line numbers

## File System Operations

Clients implement file operations; agents call them.

```typescript
class MyClient implements acp.Client {
  async readTextFile(
    params: acp.ReadTextFileRequest
  ): Promise<acp.ReadTextFileResponse> {
    // params.sessionId - Session context
    // params.path - Absolute file path
    // params.line - Optional: 1-based start line
    // params.limit - Optional: max lines to read

    const content = await fs.readFile(params.path, "utf-8");
    return { content };
  }

  async writeTextFile(
    params: acp.WriteTextFileRequest
  ): Promise<acp.WriteTextFileResponse> {
    // params.path - Absolute path (create if missing)
    // params.content - Text to write

    await fs.writeFile(params.path, params.content, "utf-8");
    return {};  // null on success
  }
}
```

Agents MUST check capabilities before calling these methods.

## Terminal Operations

Clients manage shell command execution.

```typescript
class MyClient implements acp.Client {
  private terminals = new Map<string, ChildProcess>();

  async createTerminal(
    params: acp.CreateTerminalRequest
  ): Promise<acp.CreateTerminalResponse> {
    // params.command - Shell command
    // params.args - Command arguments
    // params.env - Environment variables
    // params.cwd - Working directory (absolute)
    // params.outputByteLimit - Max output buffer

    const proc = spawn(params.command, params.args || [], {
      cwd: params.cwd,
      env: { ...process.env, ...params.env }
    });

    const terminalId = generateId();
    this.terminals.set(terminalId, proc);

    return { terminalId };  // Returns immediately
  }

  async terminalOutput(
    params: acp.TerminalOutputRequest
  ): Promise<acp.TerminalOutputResponse> {
    // Non-blocking check
    return {
      output: capturedOutput,
      truncated: false,
      exitStatus: proc.exitCode !== null
        ? { exitCode: proc.exitCode, signal: null }
        : undefined
    };
  }

  async waitForTerminalExit(
    params: acp.WaitForTerminalExitRequest
  ): Promise<acp.WaitForTerminalExitResponse> {
    // Blocks until completion
    await waitForExit(proc);
    return {
      exitCode: proc.exitCode,  // null if killed by signal
      signal: proc.signalCode   // null if normal exit
    };
  }

  async killTerminal(params: acp.KillTerminalRequest): Promise<void> {
    // Terminates command but keeps terminal valid
    proc.kill();
  }

  async releaseTerminal(params: acp.ReleaseTerminalRequest): Promise<void> {
    // Kills if running AND releases resources
    proc.kill();
    this.terminals.delete(params.terminalId);
  }
}
```

Terminals can embed in tool calls: `{ type: "terminal", terminalId: "..." }`. Display live output during execution.

## Session Persistence

If `agentCapabilities.loadSession` is true:

```typescript
// Resume existing session
const loadResult = await connection.loadSession({
  sessionId: previousSessionId,
  cwd: "/path/to/workspace",
  mcpServers: []
});

// Agent streams history via sessionUpdate notifications:
// user_message_chunk -> agent_message_chunk -> ...
```

## Cancellation

```typescript
// Send cancellation notification (doesn't wait for response)
await connection.cancel({
  sessionId: sessionId
});

// The current prompt() call will eventually return with:
// stopReason: "cancelled"
```

Continue accepting `sessionUpdate` notifications after cancellation—agents need time to wind down.

## Extension Methods

For custom protocol extensions:

```typescript
// Send custom request (expects response)
const result = await connection.extMethod("_myCustomMethod", {
  customParam: "value"
});

// Send custom notification (fire-and-forget)
await connection.extNotification("_myCustomNotification", {
  customData: "value"
});
```

Custom methods/notifications use underscore prefix by convention.

## Error Handling

```typescript
try {
  const result = await connection.prompt({ ... });
} catch (error) {
  // JSON-RPC errors have code and message
  console.error(`Error ${error.code}: ${error.message}`);
}
```

Handle connection closure via `AbortSignal`:

```typescript
connection.signal.addEventListener("abort", () => {
  console.log("Connection closed");
});

// Or await closure
await connection.closed;
```

## Integration with Gremllm Architecture

Following FCIS principles:

**Main Process** (`main/acp.cljs`):
- Spawn agent subprocess
- Create `ClientSideConnection`
- Register IPC handlers for renderer communication

**Schema** (`schema.cljs`):
- Define ACP message types with Malli
- Validation at IPC boundaries

**Effects** (`main/effects/acp.cljs`):
- `acp/initialize` - Start connection
- `acp/prompt` - Send user message
- `acp/cancel` - Cancel current operation
- Permission request forwarding to renderer

**Renderer Actions** (`renderer/actions/acp.cljs`):
- Handle permission requests from main
- Display streaming updates
- Track tool call progress

**IPC Channels:**
- `acp/session-update` - Stream updates to renderer
- `acp/request-permission` - Forward permission requests
- `acp/permission-response` - Return user decisions
- `acp/prompt` - Send prompts from renderer
- `acp/cancel` - Initiate cancellation

## Complete Client Interface

```typescript
interface Client {
  // Required
  requestPermission(params: RequestPermissionRequest): Promise<RequestPermissionResponse>;
  sessionUpdate(params: SessionNotification): Promise<void>;

  // Optional - File System (check capabilities)
  readTextFile?(params: ReadTextFileRequest): Promise<ReadTextFileResponse>;
  writeTextFile?(params: WriteTextFileRequest): Promise<WriteTextFileResponse>;

  // Optional - Terminal (check capabilities)
  createTerminal?(params: CreateTerminalRequest): Promise<CreateTerminalResponse>;
  terminalOutput?(params: TerminalOutputRequest): Promise<TerminalOutputResponse>;
  waitForTerminalExit?(params: WaitForTerminalExitRequest): Promise<WaitForTerminalExitResponse>;
  killTerminal?(params: KillTerminalRequest): Promise<void>;
  releaseTerminal?(params: ReleaseTerminalRequest): Promise<void>;

  // Optional - Extensions
  extMethod?(method: string, params: Record<string, unknown>): Promise<Record<string, unknown>>;
  extNotification?(method: string, params: Record<string, unknown>): Promise<void>;
}
```

## Quick Reference

| Operation | Method | Direction |
|-----------|--------|-----------|
| Start connection | `initialize` | Client → Agent |
| Create session | `newSession` | Client → Agent |
| Resume session | `loadSession` | Client → Agent |
| Send prompt | `prompt` | Client → Agent |
| Cancel operation | `cancel` | Client → Agent (notification) |
| Progress updates | `sessionUpdate` | Agent → Client (notification) |
| Permission request | `requestPermission` | Agent → Client |
| Read file | `readTextFile` | Agent → Client |
| Write file | `writeTextFile` | Agent → Client |
| Run command | `createTerminal` | Agent → Client |
| Get output | `terminalOutput` | Agent → Client |
| Wait for exit | `waitForTerminalExit` | Agent → Client |
| Kill command | `killTerminal` | Agent → Client |
| Release terminal | `releaseTerminal` | Agent → Client |
