#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "..");

function readLog(relativePath) {
  const absolutePath = path.join(repoRoot, relativePath);
  return fs.readFileSync(absolutePath, "utf8");
}

function allTrue(flags) {
  return flags.length > 0 && flags.every(Boolean);
}

function lineToCharIndex(content, lineNumber) {
  if (lineNumber <= 1) {
    return 0;
  }

  let currentLine = 1;
  let idx = 0;
  while (currentLine < lineNumber && idx < content.length) {
    const next = content.indexOf("\n", idx);
    if (next === -1) {
      return content.length;
    }
    idx = next + 1;
    currentLine += 1;
  }
  return idx;
}

function loadDiffMatchPatch() {
  try {
    return {
      module: require("@sanity/diff-match-patch"),
      source: "local-node_modules"
    };
  } catch (_ignored) {
    const tempRoot = path.join(os.tmpdir(), "gremllm-dmp-spike");
    const packageJsonPath = path.join(tempRoot, "package.json");
    const modulePath = path.join(tempRoot, "node_modules", "@sanity", "diff-match-patch", "dist", "index.cjs");

    fs.mkdirSync(tempRoot, { recursive: true });

    if (!fs.existsSync(packageJsonPath)) {
      fs.writeFileSync(
        packageJsonPath,
        JSON.stringify({ name: "gremllm-dmp-spike", private: true }, null, 2) + "\n",
        "utf8"
      );
    }

    if (!fs.existsSync(modulePath)) {
      const tempCache = path.join(os.tmpdir(), "gremllm-npm-cache");
      execSync("npm install --no-package-lock --no-save @sanity/diff-match-patch", {
        cwd: tempRoot,
        stdio: "pipe",
        env: { ...process.env, NPM_CONFIG_CACHE: tempCache }
      });
    }

    return {
      module: require(modulePath),
      source: tempRoot
    };
  }
}

function extractFirstGroupedCase(relativePath) {
  const raw = readLog(relativePath);
  const groupedCaseRegex =
    /:new-text "([\s\S]*?)"\n\s*:old-text "([\s\S]*?)"[\s\S]*?:locations \[\{:line (\d+)[\s\S]*?:originalFile "([\s\S]*?)"\n\s*:replaceAll/;
  const match = raw.match(groupedCaseRegex);
  if (!match) {
    throw new Error(`Could not parse grouped case from ${relativePath}`);
  }

  return {
    file: relativePath,
    newText: match[1],
    oldText: match[2],
    line: Number(match[3]),
    original: match[4]
  };
}

function extractFirstTwoPairs(relativePath) {
  const raw = readLog(relativePath);
  const pairRegex = /:new-text "([\s\S]*?)"\n\s*:old-text "([\s\S]*?)"\n\s*:path/g;
  const pairs = [];
  let match;
  while ((match = pairRegex.exec(raw)) !== null) {
    pairs.push({ newText: match[1], oldText: match[2] });
  }

  const originalMatch = raw.match(/:originalFile "([\s\S]*?)"\n\s*:replaceAll/);
  if (!originalMatch) {
    throw new Error(`Could not parse :originalFile from ${relativePath}`);
  }

  if (pairs.length < 2) {
    throw new Error(`Expected at least two diff pairs in ${relativePath}, found ${pairs.length}`);
  }

  return {
    original: originalMatch[1],
    first: pairs[0],
    second: pairs[1]
  };
}

function evaluateSnippetVsFull(docCase, dmp) {
  const { makePatches, applyPatches } = dmp;
  const actualIndex = docCase.original.indexOf(docCase.oldText);
  const snippetPatches = makePatches(docCase.oldText, docCase.newText);
  const snippetFirstStart = snippetPatches[0]?.start1 ?? null;
  const [_snippetOutput, snippetFlags] = applyPatches(snippetPatches, docCase.original);
  const snippetApplied = allTrue(snippetFlags);

  const fullReplacement = actualIndex >= 0
    ? docCase.original.replace(docCase.oldText, docCase.newText)
    : null;
  const fullPatches = fullReplacement === null ? [] : makePatches(docCase.original, fullReplacement);
  const [fullOutput, fullFlags] = fullPatches.length > 0
    ? applyPatches(fullPatches, docCase.original)
    : [docCase.original, []];

  return {
    file: docCase.file,
    line: docCase.line,
    oldLength: docCase.oldText.length,
    actualIndex,
    snippetPatchStarts: snippetPatches.map((patch) => patch.start1),
    snippetFirstStart,
    snippetApplied,
    fullPatchStarts: fullPatches.map((patch) => patch.start1),
    fullApplied: allTrue(fullFlags),
    fullEqualsDirectReplace: fullReplacement !== null ? fullOutput === fullReplacement : false
  };
}

function runSpike() {
  const loader = loadDiffMatchPatch();
  const dmp = loader.module;
  const { makePatches, applyPatches } = dmp;

  const apiSurface = {
    source: loader.source,
    hasMakePatches: typeof dmp.makePatches === "function",
    hasApplyPatches: typeof dmp.applyPatches === "function",
    hasMatch: typeof dmp.match === "function",
    hasConstructorExport: Object.prototype.hasOwnProperty.call(dmp, "diff_match_patch")
  };

  const groupedCases = [
    extractFirstGroupedCase("test/list_diff.log"),
    extractFirstGroupedCase("test/mixed_format_diff.log"),
    extractFirstGroupedCase("test/multi_paragraphs_diff.log"),
    extractFirstGroupedCase("test/long_diff.log")
  ];

  const startSemantics = groupedCases.map((docCase) => evaluateSnippetVsFull(docCase, dmp));
  const longCase = groupedCases.find((docCase) => docCase.file.endsWith("long_diff.log"));
  const multiCase = groupedCases.find((docCase) => docCase.file.endsWith("multi_paragraphs_diff.log"));

  const longSnippetPatches = makePatches(longCase.oldText, longCase.newText);
  const [_rawLongOutput, rawLongFlags] = applyPatches(longSnippetPatches, longCase.original);
  const lineOffset = lineToCharIndex(longCase.original, longCase.line);
  const adjustedPatches = structuredClone(longSnippetPatches);
  for (const patch of adjustedPatches) {
    patch.start1 += lineOffset;
    patch.start2 += lineOffset;
    patch.utf8Start1 += lineOffset;
    patch.utf8Start2 += lineOffset;
  }
  const [_adjustedLongOutput, adjustedLongFlags] = applyPatches(adjustedPatches, longCase.original);
  const longOffsetExperiment = {
    line: longCase.line,
    lineOffset,
    actualIndex: longCase.original.indexOf(longCase.oldText),
    rawSnippetApplied: allTrue(rawLongFlags),
    adjustedApplied: allTrue(adjustedLongFlags)
  };

  const listSequential = extractFirstTwoPairs("test/list_diff.log");
  const p1 = makePatches(listSequential.first.oldText, listSequential.first.newText);
  const p2 = makePatches(listSequential.second.oldText, listSequential.second.newText);
  const [afterP1, p1Flags] = applyPatches(p1, listSequential.original);
  const [_afterP1P2, p2AfterP1Flags] = applyPatches(p2, afterP1);
  const [afterP2, p2Flags] = applyPatches(p2, listSequential.original);
  const [_afterP2P1, p1AfterP2Flags] = applyPatches(p1, afterP2);
  const sequentialOverlap = {
    firstAloneApplied: allTrue(p1Flags),
    secondAloneApplied: allTrue(p2Flags),
    secondAfterFirstApplied: allTrue(p2AfterP1Flags),
    firstAfterSecondApplied: allTrue(p1AfterP2Flags),
    secondPatchFailsAfterEitherOrder: !allTrue(p2AfterP1Flags) && !allTrue(p1AfterP2Flags)
  };

  const multiParagraph = {
    containsParagraphBreaks: multiCase.oldText.includes("\n\n"),
    oldLength: multiCase.oldText.length,
    snippetApplied: startSemantics.find((caseResult) => caseResult.file.endsWith("multi_paragraphs_diff.log"))?.snippetApplied ?? false
  };

  const majorMismatches = [];
  if (!apiSurface.hasMakePatches || !apiSurface.hasApplyPatches) {
    majorMismatches.push("required functional patch APIs are missing");
  }
  if (!apiSurface.hasConstructorExport) {
    majorMismatches.push("package API differs from constructor-based proposal assumptions");
  }
  if (longOffsetExperiment.rawSnippetApplied === false) {
    majorMismatches.push("snippet patching fails on long-document real log without offset hints");
  }
  if (sequentialOverlap.secondPatchFailsAfterEitherOrder) {
    majorMismatches.push("sequential overlapping diffs are not independently composable");
  }

  const decision = majorMismatches.length > 0 ? "no-go" : "go";

  return {
    generatedAt: new Date().toISOString(),
    apiSurface,
    experiments: {
      startSemantics,
      longOffsetExperiment,
      sequentialOverlap,
      multiParagraph
    },
    decision,
    majorMismatches
  };
}

function main() {
  const report = runSpike();
  console.log(JSON.stringify(report, null, 2));

  if (process.argv.includes("--require-go") && report.decision !== "go") {
    process.exit(1);
  }
}

main();
