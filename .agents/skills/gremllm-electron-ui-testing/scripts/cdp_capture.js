#!/usr/bin/env node

const {
  createCdpClient,
  evaluateExpression,
  fetchTargets,
  normalizeRemoteObject,
  selectTarget
} = require("./cdp_eval.js");

function parseArgs(argv) {
  const parsed = {
    command: null,
    port: Number(process.env.GREMLLM_UI_TEST_PORT || 9222),
    title: process.env.GREMLLM_UI_TEST_TARGET_TITLE || "Gremllm",
    selector: ".document-panel article",
    durationMs: 1500
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
    if (arg === "--selector") {
      parsed.selector = argv[index + 1];
      index += 1;
      continue;
    }
    if (arg === "--duration-ms") {
      parsed.durationMs = Number(argv[index + 1]);
      index += 1;
      continue;
    }
    if (parsed.command === null) {
      parsed.command = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }

  if (!parsed.command || !["dom", "selection", "console"].includes(parsed.command)) {
    throw new Error("Usage: node cdp_capture.js <dom|selection|console> [--selector .document-panel article] [--duration-ms 1500] [--port 9222] [--title Gremllm]");
  }

  return parsed;
}

function domExpression(selector) {
  return `(() => {
    const selectorValue = ${JSON.stringify(selector)};
    const node = document.querySelector(selectorValue);
    if (!node) {
      return { found: false, selector: selectorValue };
    }
    return {
      found: true,
      selector: selectorValue,
      text: node.innerText,
      html: node.outerHTML
    };
  })()`;
}

function selectionExpression() {
  return `(() => {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.toString() === "") {
      return { found: false, text: "" };
    }

    const range = selection.getRangeAt(0);
    const ancestor = range.commonAncestorContainer.nodeType === Node.TEXT_NODE
      ? range.commonAncestorContainer.parentElement
      : range.commonAncestorContainer;

    return {
      found: true,
      text: selection.toString(),
      anchorNode: selection.anchorNode ? selection.anchorNode.nodeName : null,
      focusNode: selection.focusNode ? selection.focusNode.nodeName : null,
      commonAncestor: ancestor ? ancestor.nodeName : null
    };
  })()`;
}

async function captureDom({
  selector = ".document-panel article",
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const result = await evaluateExpression({
    expression: domExpression(selector),
    port,
    title,
    fetchImpl,
    WebSocketImpl
  });

  return {
    command: "dom",
    target: result.target,
    capture: result.result.value
  };
}

async function captureSelection({
  port = 9222,
  title = "Gremllm",
  fetchImpl = fetch,
  WebSocketImpl = WebSocket
}) {
  const result = await evaluateExpression({
    expression: selectionExpression(),
    port,
    title,
    fetchImpl,
    WebSocketImpl
  });

  return {
    command: "selection",
    target: result.target,
    capture: result.result.value
  };
}

async function captureConsole({
  port = 9222,
  title = "Gremllm",
  durationMs = 1500,
  fetchImpl = fetch,
  WebSocketImpl = WebSocket,
  setTimeoutImpl = setTimeout
}) {
  const targets = await fetchTargets(port, fetchImpl);
  const target = selectTarget(targets, title);
  const client = await createCdpClient(WebSocketImpl, target.webSocketDebuggerUrl);
  const entries = [];

  client.onEvent((payload) => {
    if (payload.method === "Runtime.consoleAPICalled") {
      entries.push({
        source: "Runtime.consoleAPICalled",
        type: payload.params.type,
        args: (payload.params.args || []).map(normalizeRemoteObject)
      });
      return;
    }

    if (payload.method === "Log.entryAdded") {
      entries.push({
        source: "Log.entryAdded",
        level: payload.params.entry.level,
        text: payload.params.entry.text
      });
    }
  });

  try {
    await client.send("Runtime.enable");
    await client.send("Log.enable");
    await new Promise((resolve) => setTimeoutImpl(resolve, durationMs));

    return {
      command: "console",
      target: { id: target.id, title: target.title },
      durationMs,
      entries
    };
  } finally {
    client.close();
  }
}

async function main(argv = process.argv.slice(2), deps = {}) {
  const { command, selector, port, title, durationMs } = parseArgs(argv);
  let result;

  if (command === "dom") {
    result = await captureDom({
      selector,
      port,
      title,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket
    });
  } else if (command === "selection") {
    result = await captureSelection({
      port,
      title,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket
    });
  } else {
    result = await captureConsole({
      port,
      title,
      durationMs,
      fetchImpl: deps.fetchImpl || fetch,
      WebSocketImpl: deps.WebSocketImpl || WebSocket,
      setTimeoutImpl: deps.setTimeoutImpl || setTimeout
    });
  }

  (deps.stdout || process.stdout).write(`${JSON.stringify(result)}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${JSON.stringify({ ok: false, step: "cdp_capture", message: error.message })}\n`);
    process.exit(1);
  });
}

module.exports = {
  captureConsole,
  captureDom,
  captureSelection,
  main,
  parseArgs
};
