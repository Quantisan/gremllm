// test/acp-dispatch.mjs
// Integration test for ACP SDK Wrapper + Dispatch pattern
import * as acp from "../resources/acp.js";

const events = [];

// Wire up dispatcher to collect events
acp.setDispatcher((eventType, data) => {
  console.log(`[Dispatch] ${eventType}:`, JSON.stringify(data, null, 2));
  events.push({ type: eventType, data });
});

async function runTest() {
  console.log("=== ACP Dispatch Integration Test ===\n");

  try {
    // 1. Initialize
    console.log("1. Initializing connection...");
    await acp.initialize();
    console.log("   ✓ Connection established\n");

    // 2. Create session
    console.log("2. Creating session...");
    const sessionId = await acp.newSession(process.cwd());
    console.log(`   ✓ Session created: ${sessionId}\n`);

    // 3. Send prompt
    console.log("3. Sending prompt...");
    const result = await acp.prompt(sessionId, "Say only: Hello");
    console.log(`   ✓ Prompt completed: stopReason=${result.stopReason}\n`);

    // 4. Verify events were dispatched
    console.log("4. Checking dispatched events...");
    const sessionUpdates = events.filter(e => e.type === "acp.events/session-update");
    console.log(`   ✓ Received ${sessionUpdates.length} session updates\n`);

    if (sessionUpdates.length === 0) {
      throw new Error("Expected session updates but received none");
    }

    // 5. Cleanup
    console.log("5. Shutting down...");
    acp.shutdown();
    console.log("   ✓ Clean shutdown\n");

    console.log("=== TEST PASSED ===");
    process.exit(0);

  } catch (error) {
    console.error("\n=== TEST FAILED ===");
    console.error(error);
    acp.shutdown();
    process.exit(1);
  }
}

// Timeout safety
setTimeout(() => {
  console.error("\n=== TEST TIMEOUT ===");
  acp.shutdown();
  process.exit(1);
}, 60000);

runTest();
