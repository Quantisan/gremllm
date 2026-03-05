# Diff-Match-Patch Context Guide Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create `context/diff-match-patch.md` as a self-contained, upstream-generic `@sanity/diff-match-patch` `v3.2.0` usage guide with neutral option framing and focused deep-dives for long-document anchoring and sequential overlap behavior.

**Architecture:** Build the guide in layers: (1) quick reference map, (2) neutral deep dives for immediate needs, (3) appendices + progressive-disclosure pointers. Keep claims tied to upstream source/tests/changelog and keep all examples JS/TS-only. Enforce quality with explicit shell checks for line count, neutrality, and API correctness markers.

**Tech Stack:** Markdown, Node/npm metadata, GitHub source/changelog/tests, shell validation (`rg`, `wc`, `sed`).

---

### Task 1: Create Guide Skeleton and Scope Guardrails

**Files:**
- Create: `context/diff-match-patch.md`
- Modify: `docs/plans/2026-03-05-diff-match-patch-context-guide-implementation.md`
- Test: `context/diff-match-patch.md` (shell validation only)

**Step 1: Create section skeleton with placeholder stubs**

```markdown
# @sanity/diff-match-patch (v3.2.0) Guide

## Purpose and Scope
## Version Snapshot
## API Map and Quick Lookup
## Core Data Model
## Deep Dive A: Long-Document Anchoring
## Deep Dive B: Sequential and Overlapping Diffs
## Unicode and Index Semantics
## Serialization and Replay
## Match Tuning
## Gotchas and Verification Checklist
## Progressive-Disclosure References
```

**Step 2: Add explicit guide constraints near the top**

```markdown
- Generic upstream reference only
- No project-specific observations
- JS/TS examples only
- Neutral options, no recommendations
```

**Step 3: Run structure check**

Run: `rg -n "^## " context/diff-match-patch.md`
Expected: all 11 section headings are present exactly once.

**Step 4: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: scaffold diff-match-patch context guide structure"
```

### Task 2: Fill Version, API Map, and Core Data Model

**Files:**
- Modify: `context/diff-match-patch.md`
- Test: `context/diff-match-patch.md` (shell/API marker checks)

**Step 1: Add version snapshot content from upstream `v3.2.0`**

Include: release date, package surface summary, notable 3.2.0 change (`xIndex` export + match options support).

**Step 2: Add API map table grouped by domains**

Include these exports explicitly:
- Diff: `makeDiff`, `cleanupSemantic`, `cleanupEfficiency`, `DIFF_*`
- Match: `match`, `MatchOptions`
- Patch: `makePatches`, `applyPatches`, `parsePatch`, `stringifyPatches`, `stringifyPatch`, `Patch`
- Utils: `xIndex`, `adjustIndiciesToUcs2`

**Step 3: Add core data model section**

Document tuple/object shapes for `Diff`, `Patch`, and `PatchResult` with minimal JS/TS examples.

**Step 4: Run API marker checks**

Run: `rg -n "makePatches|applyPatches|parsePatch|stringifyPatches|makeDiff|match|xIndex|adjustIndiciesToUcs2" context/diff-match-patch.md`
Expected: all key APIs appear at least once.

**Step 5: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: add version snapshot api map and data model"
```

### Task 3: Author Deep Dive A (Long-Document Anchoring)

**Files:**
- Modify: `context/diff-match-patch.md`
- Test: `context/diff-match-patch.md` (content and neutrality checks)

**Step 1: Add neutral problem framing for long-document anchoring**

Cover why snippet patches can behave differently from full-document patches without claiming a single correct approach.

**Step 2: Add neutral options subsection with trade-offs**

Include options (no recommendation):
1. Snippet-only patching
2. Location-seeded anchoring before patch apply
3. Full-document patch generation and apply
4. Hybrid fallback flow

**Step 3: Add JS/TS examples for each option**

Ensure all snippets use actual v3.2.0 APIs and no class constructor API.

**Step 4: Run anti-legacy API check**

Run: `rg -n "new diff_match_patch|patch_make|patch_apply|parsePatches" context/diff-match-patch.md`
Expected: no matches.

**Step 5: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: add long-document anchoring deep dive"
```

### Task 4: Author Deep Dive B (Sequential/Overlapping Diffs)

**Files:**
- Modify: `context/diff-match-patch.md`
- Test: `context/diff-match-patch.md` (content checks)

**Step 1: Add sequential apply model section**

Describe expected behavior when patches are applied against evolving text and how `applyPatches` results should be interpreted (`boolean[]`).

**Step 2: Add overlap behavior section with neutral handling options**

Include neutral options (no recommendation):
1. Keep operations independent and accept failures
2. Recompute patches against updated state
3. Merge/rebase transforms before apply
4. Flag and escalate unresolved overlaps

**Step 3: Add compact JS/TS examples**

Show success/failure inspection pattern using returned `boolean[]`.

**Step 4: Run deep-dive presence check**

Run: `rg -n "Deep Dive A|Deep Dive B|overlap|boolean\[\]" context/diff-match-patch.md`
Expected: both deep dives and overlap result handling are present.

**Step 5: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: add sequential and overlap deep dive"
```

### Task 5: Add Appendices, Pointers, and Verification Checklist

**Files:**
- Modify: `context/diff-match-patch.md`
- Test: `context/diff-match-patch.md` (neutrality and pointer checks)

**Step 1: Add Unicode/index semantics appendix**

Cover `utf8Start*`/`utf8Length*` vs `start*`/`length*`, and `adjustIndiciesToUcs2` + `allowExceedingIndices`.

**Step 2: Add serialization/replay appendix**

Cover `stringifyPatches`/`parsePatch` round-trip and patch text handling details.

**Step 3: Add match tuning appendix**

Document `match()` options (`threshold`, `distance`) and trade-offs.

**Step 4: Add progressive-disclosure reference list**

Point to specific upstream files and tests (README, CHANGELOG, `src/index.ts`, key `src/**/__tests__`, relevant Sanity usage examples).

**Step 5: Add final verification checklist section**

Checklist must include:
- API correctness spot-check
- Neutrality check
- Legacy API typo check
- Line count check (<1000)

**Step 6: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: add appendices references and verification checklist"
```

### Task 6: Final Validation and Polish Pass

**Files:**
- Modify: `context/diff-match-patch.md`
- Test: `context/diff-match-patch.md` (final quality gates)

**Step 1: Run line-count gate**

Run: `wc -l context/diff-match-patch.md`
Expected: line count is `< 1000`.

**Step 2: Run neutrality gate**

Run: `rg -n "gremllm|local spike|anchoring-proposal|spike-design|test/diff_match_patch_spike" context/diff-match-patch.md`
Expected: no matches.

**Step 3: Run JS/TS-only API gate**

Run: `rg -n "Clojure|ClojureScript|cljs|js/require" context/diff-match-patch.md`
Expected: no matches.

**Step 4: Run final upstream API sanity check**

Run: `rg -n "parsePatch|makePatches|applyPatches|match\(|xIndex|adjustIndiciesToUcs2" context/diff-match-patch.md`
Expected: key APIs are present and named correctly.

**Step 5: Manual clarity pass**

Trim repetition, keep sections concise, keep options neutral, and ensure progressive-disclosure references are specific.

**Step 6: Commit**

```bash
git add context/diff-match-patch.md
git commit -m "docs: finalize diff-match-patch context guide"
```

### Task 7: Final Review and Handoff Notes

**Files:**
- Modify: `context/diff-match-patch.md` (only if review findings require edits)
- Test: `context/diff-match-patch.md`

**Step 1: Verify all quality-bar criteria from design doc**

Cross-check against:
- `docs/plans/2026-03-05-diff-match-patch-context-guide-design.md`

**Step 2: Produce short handoff summary in commit message/body or PR description**

Include:
- what changed
- why structure is layered
- where to read deeper (reference section)

**Step 3: Optional final commit (only if changes were made)**

```bash
git add context/diff-match-patch.md
git commit -m "docs: apply final review adjustments for diff-match-patch guide"
```
