import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const capture = require("../../../.agents/skills/gremllm-electron-ui-testing/scripts/cdp_capture.js");

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

    if (message.method === "Runtime.enable" || message.method === "Log.enable") {
      queueMicrotask(() => this.#emit("message", { data: JSON.stringify({ id: message.id, result: {} }) }));
      if (message.method === "Log.enable") {
        queueMicrotask(() =>
          this.#emit("message", {
            data: JSON.stringify({
              method: "Runtime.consoleAPICalled",
              params: {
                type: "log",
                args: [{ type: "string", value: "captured", description: "captured" }]
              }
            })
          })
        );
      }
      return;
    }

    if (message.method === "Runtime.evaluate") {
      const value = message.params.expression.includes("window.getSelection")
        ? {
            found: true,
            text: "selected text",
            anchorNode: "#text",
            focusNode: "#text",
            commonAncestor: "P"
          }
        : {
            found: true,
            selector: ".document-panel article",
            text: "Fixture text",
            html: "<article>Fixture text</article>"
          };

      queueMicrotask(() =>
        this.#emit("message", {
          data: JSON.stringify({
            id: message.id,
            result: {
              result: {
                type: "object",
                value,
                description: "Object"
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

const fetchImpl = async () => ({
  ok: true,
  json: async () => ([
    {
      id: "page-1",
      type: "page",
      title: "Gremllm",
      webSocketDebuggerUrl: "ws://127.0.0.1:9222/devtools/page/1"
    }
  ])
});

test("captureDom returns normalized article output", async () => {
  const result = await capture.captureDom({
    selector: ".document-panel article",
    port: 9222,
    title: "Gremllm",
    fetchImpl,
    WebSocketImpl: FakeWebSocket
  });

  assert.equal(result.capture.selector, ".document-panel article");
  assert.equal(result.capture.text, "Fixture text");
});

test("captureSelection returns normalized selection details", async () => {
  const result = await capture.captureSelection({
    port: 9222,
    title: "Gremllm",
    fetchImpl,
    WebSocketImpl: FakeWebSocket
  });

  assert.equal(result.capture.text, "selected text");
  assert.equal(result.capture.commonAncestor, "P");
});

test("captureConsole collects Runtime.consoleAPICalled events", async () => {
  const result = await capture.captureConsole({
    port: 9222,
    title: "Gremllm",
    durationMs: 0,
    fetchImpl,
    WebSocketImpl: FakeWebSocket,
    setTimeoutImpl: (callback) => callback()
  });

  assert.equal(result.entries.length, 1);
  assert.equal(result.entries[0].source, "Runtime.consoleAPICalled");
  assert.equal(result.entries[0].args[0].value, "captured");
});
