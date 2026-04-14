#!/usr/bin/env node

function parseArgs(argv) {
  const parsed = {
    port: Number(process.env.GREMLLM_UI_TEST_PORT || 9222),
    title: process.env.GREMLLM_UI_TEST_TARGET_TITLE || "Gremllm",
    expression: null
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--port") {
      parsed.port = Number(argv[index + 1]);
      index += 1;
      continue;
    }
    if (arg === "--title") {
      parsed.title = argv[index + 1];
      index += 1;
      continue;
    }
    if (parsed.expression === null) {
      parsed.expression = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }

  if (!parsed.expression) {
    throw new Error("Usage: node cdp_eval.js [--port 9222] [--title Gremllm] '<expression>'");
  }

  return parsed;
}

async function fetchTargets(port, fetchImpl = fetch) {
  const response = await fetchImpl(`http://127.0.0.1:${port}/json/list`);
  if (!response.ok) {
    throw new Error(`CDP target list request failed with status ${response.status}`);
  }
  return response.json();
}

function selectTarget(targets, title) {
  const target = targets.find((candidate) => candidate.type === "page" && candidate.title === title);
  if (!target) {
    throw new Error(`Renderer target with title "${title}" not found`);
  }
  return target;
}

function normalizeRemoteObject(remoteObject = {}) {
  if (Object.prototype.hasOwnProperty.call(remoteObject, "value")) {
    return {
      type: remoteObject.type,
      value: remoteObject.value,
      description: remoteObject.description || remoteObject.type
    };
  }

  if (Object.prototype.hasOwnProperty.call(remoteObject, "unserializableValue")) {
    return {
      type: remoteObject.type,
      value: remoteObject.unserializableValue,
      description: remoteObject.description || remoteObject.type
    };
  }

  return {
    type: remoteObject.type || "unknown",
    value: null,
    description: remoteObject.description || remoteObject.type || "unknown"
  };
}

function createCdpClient(WebSocketImpl, wsUrl) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocketImpl(wsUrl);
    const pending = new Map();
    const eventListeners = [];
    let nextId = 0;
    let opened = false;

    const rejectPending = (error) => {
      for (const entry of pending.values()) {
        entry.reject(error);
      }
      pending.clear();
    };

    socket.addEventListener("open", () => {
      opened = true;
      resolve({
        async send(method, params = {}) {
          const id = nextId += 1;
          const payload = { id, method, params };
          return new Promise((innerResolve, innerReject) => {
            pending.set(id, { resolve: innerResolve, reject: innerReject });
            socket.send(JSON.stringify(payload));
          });
        },
        onEvent(listener) {
          eventListeners.push(listener);
        },
        close() {
          socket.close();
        }
      });
    });

    socket.addEventListener("message", (event) => {
      const payload = JSON.parse(event.data);
      if (Object.prototype.hasOwnProperty.call(payload, "id")) {
        const entry = pending.get(payload.id);
        if (!entry) {
          return;
        }
        pending.delete(payload.id);
        if (payload.error) {
          entry.reject(new Error(payload.error.message || "CDP request failed"));
          return;
        }
        entry.resolve(payload.result);
        return;
      }

      for (const listener of eventListeners) {
        listener(payload);
      }
    });

    socket.addEventListener("error", (event) => {
      const error = new Error(event.message || "CDP websocket error");
      if (!opened) {
        reject(error);
        return;
      }
      rejectPending(error);
    });

    socket.addEventListener("close", () => {
      rejectPending(new Error("CDP websocket closed"));
    });
  });
}

async function evaluateExpression({
  expression,
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const targets = await fetchTargets(port, fetchImpl);
  const target = selectTarget(targets, title);
  const client = await createCdpClient(WebSocketImpl, target.webSocketDebuggerUrl);

  try {
    await client.send("Runtime.enable");
    const evaluation = await client.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true
    });

    if (evaluation.exceptionDetails) {
      throw new Error(evaluation.exceptionDetails.text || "Runtime evaluation failed");
    }

    return {
      target: { id: target.id, title: target.title },
      result: normalizeRemoteObject(evaluation.result)
    };
  } finally {
    client.close();
  }
}

async function main(argv = process.argv.slice(2), deps = {}) {
  const { port, title, expression } = parseArgs(argv);
  const result = await evaluateExpression({
    expression,
    port,
    title,
    fetchImpl: deps.fetchImpl || fetch,
    WebSocketImpl: deps.WebSocketImpl || WebSocket
  });
  (deps.stdout || process.stdout).write(`${JSON.stringify(result)}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${JSON.stringify({ ok: false, step: "cdp_eval", message: error.message })}\n`);
    process.exit(1);
  });
}

module.exports = {
  createCdpClient,
  evaluateExpression,
  fetchTargets,
  main,
  normalizeRemoteObject,
  parseArgs,
  selectTarget
};
