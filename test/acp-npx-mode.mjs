#!/usr/bin/env node

import assert from "node:assert/strict";
import acpModule from "../resources/acp/index.js";

const buildNpxSpawnConfig = acpModule.__test__?.buildNpxSpawnConfig;

assert.equal(
  typeof buildNpxSpawnConfig,
  "function",
  "Expected __test__.buildNpxSpawnConfig export"
);

{
  const config = buildNpxSpawnConfig({ isPackaged: false });
  assert.equal(config.command, "npx");
  assert.deepEqual(config.args, [
    "--yes",
    "--package=@zed-industries/claude-agent-acp@latest",
    "--",
    "claude-agent-acp"
  ]);
  assert.equal(config.envPatch.npm_config_prefer_online, "true");
}

{
  const config = buildNpxSpawnConfig({ isPackaged: true });
  assert.equal(config.command, "npx");
  assert.deepEqual(config.args, ["@zed-industries/claude-agent-acp"]);
  assert.deepEqual(config.envPatch, {});
}

console.log("acp-npx-mode: all tests passed");
