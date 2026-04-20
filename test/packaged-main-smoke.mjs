import { existsSync } from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";
import process from "node:process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);

function resolveAppAsarPath(inputPath) {
  const absoluteInput = path.resolve(inputPath);
  const candidates = absoluteInput.endsWith(".asar")
    ? [absoluteInput]
    : [
        path.join(absoluteInput, "app.asar"),
        path.join(absoluteInput, "Resources", "app.asar"),
        path.join(absoluteInput, "Contents", "Resources", "app.asar")
      ];

  const match = candidates.find(existsSync);
  if (!match) {
    throw new Error(`Could not find app.asar from input: ${inputPath}`);
  }

  return match;
}

function waitForExit(child) {
  return new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("exit", (code, signal) => resolve({ code, signal }));
  });
}

async function main() {
  const inputPath = process.argv[2];
  if (!inputPath) {
    console.error("Usage: node test/packaged-main-smoke.mjs <packaged-app-root-or-app.asar>");
    process.exit(1);
  }

  const appAsarPath = resolveAppAsarPath(inputPath);
  const mainEntryPath = path.join(appAsarPath, "target", "main.js");

  const electronBinary = require("electron");
  const child = spawn(electronBinary, ["-e", `require(${JSON.stringify(mainEntryPath)})`], {
    env: {
      ...process.env,
      ELECTRON_RUN_AS_NODE: "1"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  child.stdout.on("data", chunk => process.stdout.write(chunk));
  child.stderr.on("data", chunk => process.stderr.write(chunk));

  const { code, signal } = await waitForExit(child);

  if (signal) {
    console.error(`Packaged main smoke exited via signal: ${signal}`);
    process.exit(1);
  }

  process.exit(code ?? 1);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
