import { spawn } from "node:child_process";
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

// Spawn subprocess (reuse Phase 1 pattern)
const proc = spawn("npx", ["@zed-industries/claude-code-acp"], {
  stdio: ["pipe", "pipe", "inherit"],
  env: { ...process.env }
});

console.log(`Spawned with PID: ${proc.pid}`);

// Create minimal TestClient implementing required interface
class TestClient {
  async requestPermission(request) {
    console.log("Permission requested:", JSON.stringify(request, null, 2));
    // Grant all permissions for testing
    return { allowed: true };
  }

  async sessionUpdate(update) {
    console.log("Session update:", JSON.stringify(update, null, 2));
  }
}

// Convert Node streams → Web streams (following acp_client.md pattern)
const input = Writable.toWeb(proc.stdin);
const output = Readable.toWeb(proc.stdout);

// Create NDJSON stream
const stream = acp.ndJsonStream(input, output);

// Create client instance
const client = new TestClient();

// Create connection
const connection = new acp.ClientSideConnection(() => client, stream);

// Perform protocol handshake
(async () => {
  try {
    console.log("\nInitializing connection...");
    const response = await connection.initialize({
      protocolVersion: acp.PROTOCOL_VERSION,
      clientCapabilities: { fs: {}, terminal: false },
      clientInfo: {
        name: "gremllm-test",
        title: "Gremllm ACP Test",
        version: "0.1.0"
      }
    });

    console.log("\nInitialize response:");
    console.log("  Protocol Version:", response.protocolVersion);
    console.log("  Agent Capabilities:", JSON.stringify(response.agentCapabilities, null, 2));

    // Clean shutdown
    console.log("\nShutting down...");
    proc.kill("SIGTERM");
  } catch (error) {
    console.error("Error during handshake:", error);
    proc.kill("SIGTERM");
    process.exit(1);
  }
})();

// Set timeout to prevent hanging
setTimeout(() => {
  console.log("\nTimeout reached, killing process");
  proc.kill("SIGTERM");
}, 30000);

proc.on("exit", (code, signal) => {
  console.log(`\nProcess exited: code=${code}, signal=${signal}`);
});
