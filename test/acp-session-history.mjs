// Minimal test - reuses resources/acp/index.js logic
import { spawn } from "node:child_process";
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

let response = "";

let historyUpdates = [];

const client = {
  async sessionUpdate(params) {
    const updateType = params.update.sessionUpdate;

    // Track all update types during resume
    if (updateType === "user_message_chunk" || updateType === "agent_message_chunk") {
      historyUpdates.push(updateType);
    }

    if (updateType === "agent_message_chunk") {
      const text = params.update.content?.text || "";
      response += text;
      process.stdout.write(text);
    } else if (updateType === "user_message_chunk") {
      const text = params.update.content?.text || "";
      process.stdout.write(`[USER: ${text}]`);
    }
  },
  async requestPermission(params) {
    return { outcome: { outcome: "selected", optionId: params.options[0].optionId } };
  }
};

async function createConnection() {
  const proc = spawn("npx", ["@zed-industries/claude-agent-acp"], {
    stdio: ["pipe", "pipe", "inherit"],
    env: process.env
  });

  const stream = acp.ndJsonStream(
    Writable.toWeb(proc.stdin),
    Readable.toWeb(proc.stdout)
  );
  const conn = new acp.ClientSideConnection(() => client, stream);

  const initResult = await conn.initialize({
    protocolVersion: acp.PROTOCOL_VERSION,
    clientCapabilities: { fs: {}, terminal: false },
    clientInfo: { name: "test", title: "Test", version: "0.0.1" }
  });

  return { conn, proc, initResult };
}

async function main() {
  // First connection
  console.log("=== CONNECTION 1 ===");
  let { conn, proc, initResult } = await createConnection();

  console.log("\n--- Agent Capabilities ---");
  console.log(JSON.stringify(initResult.agentCapabilities, null, 2));
  console.log("loadSession supported:", initResult.agentCapabilities?.loadSession ?? false);

  const { sessionId } = await conn.newSession({ cwd: process.cwd(), mcpServers: [] });
  console.log("Session:", sessionId);

  console.log("\n--- Prompt 1: My name is Alice ---");
  response = "";
  await conn.prompt({ sessionId, prompt: [{ type: "text", text: "My name is Alice. Just acknowledge briefly." }] });

  console.log("\n\n--- Disconnecting... ---");
  proc.kill("SIGTERM");
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Second connection
  console.log("\n=== CONNECTION 2 (New Process) ===");
  ({ conn, proc, initResult } = await createConnection());

  const supportsResume = initResult.agentCapabilities?.sessionCapabilities?.resume !== undefined;
  const supportsLoad = initResult.agentCapabilities?.loadSession === true;

  console.log(`Capabilities: resume=${supportsResume}, loadSession=${supportsLoad}`);
  console.log("Testing sessionId:", sessionId);

  // Test unstable_resumeSession
  if (supportsResume) {
    console.log("\n--- Testing unstable_resumeSession ---");
    try {
      historyUpdates = [];
      await conn.unstable_resumeSession({
        sessionId,
        cwd: process.cwd(),
        mcpServers: []
      });
      console.log("✓ unstable_resumeSession succeeded");
      console.log("  History updates received:", historyUpdates.length ? historyUpdates.join(", ") : "none");

      response = "";
      await conn.prompt({ sessionId, prompt: [{ type: "text", text: "What is my name?" }] });
      console.log("\n✓ Response mentions Alice?", response.toLowerCase().includes("alice"));
    } catch (err) {
      console.log("✗ unstable_resumeSession failed:", err.message);
      if (err.data) console.log("  Details:", err.data);
    }
  }

  // Test loadSession (need a fresh connection since we already resumed)
  console.log("\n--- Testing loadSession (fresh connection) ---");
  proc.kill("SIGTERM");
  await new Promise(resolve => setTimeout(resolve, 1000));

  console.log("=== CONNECTION 3 ===");
  ({ conn, proc, initResult } = await createConnection());

  try {
    console.log("Calling loadSession...");
    historyUpdates = [];
    await conn.loadSession({
      sessionId,
      cwd: process.cwd(),
      mcpServers: []
    });
    console.log("✓ loadSession succeeded");
    console.log("  History updates received:", historyUpdates.length ? historyUpdates.join(", ") : "none");

    response = "";
    await conn.prompt({ sessionId, prompt: [{ type: "text", text: "What is my name?" }] });
    console.log("\n✓ Response mentions Alice?", response.toLowerCase().includes("alice"));
  } catch (err) {
    console.log("✗ loadSession failed:", err.message);
    if (err.data) console.log("  Details:", err.data);
  }

  proc.kill("SIGTERM");
}

main().catch(console.error);
