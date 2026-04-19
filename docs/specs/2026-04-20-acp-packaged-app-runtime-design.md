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

Change the ACP bridge import away from package-style resolution and load the bridge directly from repo-local source, from both production and test entrypoints.

**Path semantics note:** Shadow-cljs `:require` paths are resolved relative to the *source* CLJS file, not the compiled output. From `src/gremllm/main/effects/acp.cljs` the source-relative path is `../../../../resources/acp/index.js`. Whether shadow rewrites this to a node-resolvable `require()` in `target/main.js` is an empirical question that must be verified before committing to this option.

### Advantages

- Removes the exact failing `asar` symlink boundary.
- Matches an existing working repo pattern in `test/acp-session-history.mjs`.
- Simplifies package metadata by removing the artificial local package alias.
- Keeps the ACP bridge clearly repo-local instead of packaging it as a pseudo-dependency.

### Tradeoffs

- Source-relative path semantics in shadow-cljs must be verified empirically before implementation.
- Internal consumers lose the convenience alias `require("acp")`.
- If the build output path changes later, the relative import path must be updated too.

## Option B: Preserve Package-Style Import via Packaging Changes

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

## Option C: Shadow-CLJS `:js-options :resolve` Remap

Add one line to `shadow-cljs.edn` so shadow resolves the bare `"acp"` import directly to the bridge file, bypassing npm entirely:

```clojure
:js-options {:resolve {"acp" {:target :file :file "resources/acp/index.js"}}}
```

Then delete `"acp": "file:./resources/acp"` from `package.json` and delete `resources/acp/package.json`. The `node_modules/acp` symlink disappears on the next `npm install`.

### Advantages

- Preserves `(:require ["acp" :as acp-factory])` unchanged in CLJS production and test files — zero call-site edits.
- Removes the symlink and pseudo-package alias via a documented shadow-cljs mechanism, not a packaging hack.
- Minimal diff: one `shadow-cljs.edn` line added, two files deleted.
- Lowest test-file churn of all options.

### Tradeoffs

- Shadow's emitted `require()` for `:target :file` must be verified empirically to confirm it resolves at runtime from `target/main.js` and from within `app.asar`.
- Less familiar mechanism than a plain relative import; the resolution indirection lives in build config rather than source.

## Option D: Move Bridge into `src/`

Move `resources/acp/index.js` and `resources/acp/permission.js` into `src/js/acp/` (or an equivalent location on the shadow-cljs source path) and use a source-relative CLJS `:require`.

### Advantages

- Removes the whole pseudo-package fiction: bridge code lives with the code that uses it.
- Shadow-cljs source-relative JS requires are idiomatic and well-trodden in this build setup.
- No symlink, no npm alias, no packaging special case.

### Tradeoffs

- Larger diff: two files move.
- `test/acp-session-history.mjs:9` hard-codes `require("../resources/acp/index.js")` and would need a path update.
- Requires choosing and validating the new source path against shadow-cljs classpath configuration.

## Comparison

| Criterion | Option A: Direct import | Option B: Packaging changes | Option C: shadow `:resolve` | Option D: Move to `src/` |
|-----------|-------------------------|-----------------------------|------------------------------|--------------------------|
| Addresses verified root cause directly | Yes | Indirectly | Yes | Yes |
| Keeps current `require("acp")` shape | No | Yes | Yes | No |
| Adds packaging complexity | Low | Medium to high | None | None |
| Call-site edits required | 2 files (CLJS + test) | None | None | 2 files (CLJS + mjs) |
| Shadow-cljs emission must be verified | Yes | No | Yes | No (idiomatic) |
| Already matches a working repo pattern | Partially | No | No | Yes (source-relative JS) |
| Packaged runtime validated yet | Partially, by direct archive require | No | No | No |

## Decision Criteria

The chosen fix must:

- pass a deterministic packaged-main smoke check against `app.asar/target/main.js`
- preserve ACP loading in dev and test modes
- keep `npm run package` and `npm run make` working
- avoid manual release-only steps
- minimize new packaging special cases

## Working Recommendation

Current evidence favors **Option C** (shadow `:js-options :resolve`) because it removes the exact failing indirection, requires no call-site edits, and adds no packaging complexity. Option C's shadow emission behavior must be verified empirically before committing: compile `target/main.js` with the change in place and confirm the emitted `require()` is node-resolvable from both `target/` and inside `app.asar`.

If Option C's emitted require cannot be validated, **Option D** (move to `src/`) is the next cleanest choice — it uses a well-understood shadow-cljs mechanism at the cost of moving two files.

**Option A** is the fallback if both C and D fail empirical verification. Its path-semantics behavior under shadow compilation also needs to be verified before implementation.

**Option B** stays viable only if review rejects all other options on design grounds.
