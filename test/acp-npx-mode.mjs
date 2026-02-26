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
  const config = buildNpxSpawnConfig("latest");
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
  const config = buildNpxSpawnConfig("cached");
  assert.equal(config.command, "npx");
  assert.deepEqual(config.args, ["@zed-industries/claude-agent-acp"]);
  assert.deepEqual(config.envPatch, {});
}

{
  const config = buildNpxSpawnConfig("not-a-valid-mode");
  assert.deepEqual(config.args, ["@zed-industries/claude-agent-acp"]);
}

console.log("acp-npx-mode: all tests passed");
