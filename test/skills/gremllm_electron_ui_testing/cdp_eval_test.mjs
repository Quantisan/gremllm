import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const cdpEval = require("../../../.agents/skills/gremllm-electron-ui-testing/scripts/cdp_eval.js");

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    queueMicrotask(() => this.#emit("open", {}));
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send(rawMessage) {
    const message = JSON.parse(rawMessage);

    if (message.method === "Runtime.enable") {
      queueMicrotask(() => this.#emit("message", { data: JSON.stringify({ id: message.id, result: {} }) }));
      return;
    }

    if (message.method === "Runtime.evaluate") {
      queueMicrotask(() =>
        this.#emit("message", {
          data: JSON.stringify({
            id: message.id,
            result: {
              result: {
                type: "string",
                value: "Gremllm",
                description: "Gremllm"
              }
            }
          })
        })
      );
    }
  }

  close() {
    this.#emit("close", {});
  }

  #emit(type, payload) {
    for (const listener of this.listeners.get(type) || []) {
      listener(payload);
    }
  }
}

test("evaluateExpression returns normalized JSON from the Gremllm target", async () => {
  const result = await cdpEval.evaluateExpression({
    expression: "document.title",
    port: 9222,
    title: "Gremllm",
    fetchImpl: async () => ({
      ok: true,
      json: async () => ([
        {
          id: "page-1",
          type: "page",
          title: "Gremllm",
          webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/1"
        }
      ])
    }),
    WebSocketImpl: FakeWebSocket
  });

  assert.deepEqual(result, {
    target: { id: "page-1", title: "Gremllm" },
    result: { type: "string", value: "Gremllm", description: "Gremllm" }
  });
});

test("evaluateExpression throws a clear error when the renderer target is missing", async () => {
  await assert.rejects(
    () =>
      cdpEval.evaluateExpression({
        expression: "document.title",
        port: 9222,
        title: "Gremllm",
        fetchImpl: async () => ({
          ok: true,
          json: async () => ([
            {
              id: "page-2",
              type: "page",
              title: "Other App",
              webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/2"
            }
          ])
        }),
        WebSocketImpl: FakeWebSocket
      }),
    /Renderer target with title "Gremllm" not found/
  );
});
