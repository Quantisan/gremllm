# Design: ACP Packaged-App Runtime Fix

**Date:** 2026-04-20
**Status:** Draft for review
**Related:** [docs/specs/2026-04-20-macos-only-packaging-design.md](/Users/paul/Projects/gremllm/docs/specs/2026-04-20-macos-only-packaging-design.md), [docs/plans/2026-04-20-macos-only-packaging-cleanup.md](/Users/paul/Projects/gremllm/docs/plans/2026-04-20-macos-only-packaging-cleanup.md)

## Goal

Fix the packaged macOS app startup failure caused by ACP bridge loading without changing ACP behavior, widening packaging scope, or undoing the macOS-only Forge cleanup.

## Verified Problem

- Packaged app startup fails with `ENOENT, resources/acp not found in .../app.asar`.
- Main-process ACP code imports `"acp"` from `src/gremllm/main/effects/acp.cljs`.
- Root `package.json` resolves that through `"acp": "file:./resources/acp"`.
- npm installs `node_modules/acp` as a symlink to `../resources/acp`.
- `app.asar` contains `resources/acp/index.js`, but Electron's `asar` loader cannot resolve the symlinked `node_modules/acp` entry at runtime.
- The failure is deterministic from the packaged artifact itself:

```bash
ELECTRON_RUN_AS_NODE=1 ./node_modules/.bin/electron -e "require('./out/Gremllm-darwin-arm64/Gremllm.app/Contents/Resources/app.asar/target/main.js')"
```

- Direct loading of the bridge file from the same archive succeeds:

```bash
ELECTRON_RUN_AS_NODE=1 ./node_modules/.bin/electron -e "require('./out/Gremllm-darwin-arm64/Gremllm.app/Contents/Resources/app.asar/resources/acp')"
```

## Scope

This design covers only the ACP bridge loading boundary for packaged main-process startup.

It does not cover:

- ACP protocol changes
- renderer behavior changes
- prompt construction changes
- Electron Forge migration
- signing or notarization
- non-macOS packaging

## Option A: Direct File Import

Change the ACP bridge import away from package-style resolution and load the bridge directly from repo-local source, e.g. `../resources/acp/index.js`, from both production and test entrypoints.

### Advantages

- Removes the exact failing `asar` symlink boundary.
- Matches an existing working repo pattern in `test/acp-session-history.mjs`.
- Simplifies package metadata by removing the artificial local package alias.
- Keeps the ACP bridge clearly repo-local instead of packaging it as a pseudo-dependency.

### Tradeoffs

- The import becomes coupled to the current compiled output layout under `target/`.
- Internal consumers lose the convenience alias `require("acp")`.
- If the build output path changes later, the relative import path must be updated too.

## Option B: Preserve Package-Style Import

Keep `require("acp")`, but change dependency materialization or packaging behavior so the packaged app contains a resolvable ACP module instead of an `asar`-broken symlink.

### Advantages

- Preserves the current import shape in CLJS and tests.
- Keeps call sites looking like normal dependency imports.
- Avoids a direct relative path from compiled output back into `resources/`.

### Tradeoffs

- Adds packaging-specific complexity to a local source module.
- Must still satisfy both Shadow-CLJS dev/watch behavior and packaged Electron runtime behavior.
- Risks turning a local code-loading problem into a more fragile release-only packaging rule.
- Has no validated implementation in this repo yet.

## Comparison

| Criterion | Option A: Direct import | Option B: Keep local package |
|-----------|-------------------------|------------------------------|
| Addresses verified root cause directly | Yes | Indirectly |
| Keeps current `require("acp")` shape | No | Yes |
| Adds packaging complexity | Low | Medium to high |
| Depends on current build output layout | Yes | Less directly |
| Already matches a working repo pattern | Yes | No |
| Packaged runtime validated yet | Partially, by direct archive require | No |

## Decision Criteria

The chosen fix must:

- pass a deterministic packaged-main smoke check against `app.asar/target/main.js`
- preserve ACP loading in dev and test modes
- keep `npm run package` and `npm run make` working
- avoid manual release-only steps
- minimize new packaging special cases

## Working Recommendation

Current evidence favors Option A because it removes the exact failing indirection and simplifies the model.

This design intentionally remains decision-open for review. Option B stays viable only if it can meet the same verification bar without adding more packaging complexity than Option A.
