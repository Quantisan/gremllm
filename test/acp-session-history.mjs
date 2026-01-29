// Minimal test - reuses resources/acp/index.js logic
import { spawn } from "node:child_process";
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

let response = "";

const client = {
  async sessionUpdate(params) {
    if (params.update.sessionUpdate === "agent_message_chunk") {
      const text = params.update.content?.text || "";
      response += text;
      process.stdout.write(text);
    }
  },
  async requestPermission(params) {
    return { outcome: { outcome: "selected", optionId: params.options[0].optionId } };
  }
};

async function createConnection() {
  const proc = spawn("npx", ["@zed-industries/claude-code-acp"], {
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

  if (!supportsResume && !supportsLoad) {
    console.log("\n--- Result ---");
    console.log("✗ Agent does NOT support session resumption");
    console.log("Sessions cannot persist across connection restarts with this agent.");
    proc.kill("SIGTERM");
    return;
  }

  console.log(`✓ Agent supports: ${supportsResume ? 'resume' : ''} ${supportsLoad ? 'load' : ''}`);
  console.log("Attempting to resume sessionId:", sessionId);

  try {
    if (supportsResume) {
      console.log("\n--- Resuming session (unstable_resumeSession) ---");
      await conn.unstable_resumeSession({
        sessionId,
        cwd: process.cwd(),
        mcpServers: []
      });
    } else {
      console.log("\n--- Loading session (loadSession) ---");
      await conn.loadSession({
        sessionId,
        cwd: process.cwd(),
        mcpServers: []
      });
    }

    console.log("\n--- Prompt 2: What is my name? ---");
    response = "";
    await conn.prompt({ sessionId, prompt: [{ type: "text", text: "What is my name?" }] });

    console.log("\n\n--- Result ---");
    console.log("✓ Session resumed successfully!");
    console.log("If response mentions 'Alice', history persists across connections.");
  } catch (err) {
    console.log("\n\n--- Result ---");
    console.log("✗ Could not resume session:", err.message);
    if (err.data) console.log("Error details:", err.data);
  }

  proc.kill("SIGTERM");
}

main().catch(console.error);
