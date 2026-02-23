#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import acpModule from "../resources/acp/index.js";

const readTextFileFromDisk = acpModule.__test__?.readTextFileFromDisk;

assert.equal(
  typeof readTextFileFromDisk,
  "function",
  "Expected __test__.readTextFileFromDisk export"
);

const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "acp-fs-bridge-"));

try {
  const filePath = path.join(tempDir, "document.md");
  const content = [
    "line-1",
    "line-2",
    "line-3",
    "line-4",
    ""
  ].join("\n");
  await fs.writeFile(filePath, content, "utf8");

  {
    const result = await readTextFileFromDisk({ path: filePath });
    assert.equal(result.content, content);
  }

  {
    const result = await readTextFileFromDisk({
      path: filePath,
      line: 2,
      limit: 2
    });
    assert.equal(result.content, "line-2\nline-3");
  }

  {
    const result = await readTextFileFromDisk({
      path: filePath,
      line: 3
    });
    assert.equal(result.content, "line-3\nline-4\n");
  }

  {
    const result = await readTextFileFromDisk({
      path: filePath,
      line: 99,
      limit: 2
    });
    assert.equal(result.content, "");
  }

  {
    const missingPath = path.join(tempDir, "missing.md");
    await assert.rejects(
      readTextFileFromDisk({ path: missingPath }),
      /ENOENT/
    );
  }
} finally {
  await fs.rm(tempDir, { recursive: true, force: true });
}

console.log("acp-fs-bridge: all tests passed");
