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

## Shadow-CLJS Resolution Primer (verified against official docs)

Shadow-CLJS resolves CLJS `:require` strings in three ways, per the official [JS Dependencies](https://github.com/shadow-cljs/shadow-cljs.github.io/blob/master/docs/js-deps.adoc) guide:

1. **Bare string** (e.g. `"acp"`) → looked up in `node_modules`.
2. **Absolute classpath** (e.g. `"/js/acp/index"`) → file must exist on the CLJS classpath as `js/acp/index.js`. The shadow-cljs docs recommend this explicitly for "local JS files you wrote."
3. **Relative classpath** (e.g. `"./bridge"`) → resolved relative to the current CLJS namespace's classpath location. Cannot climb above the classpath root.

Project classpath (`deps.edn:1`, `:dev` alias): `src`, `test`, `dev`. `resources/` is **not** on the classpath.

Additional mechanism: `:js-options {:resolve {"name" {:target :file :file "..."}}}` remaps a bare require to a specific file. Docs note this has **limitations under `:js-provider :require` (Node.js targets)** because "downstream requires escape control." Our `:node-script` builds use this provider by default, and the bridge has downstream requires for `@agentclientprotocol/sdk`, `./permission`, etc., so this mechanism is in the warned-against region.

## Option A: Move Bridge to `src/` and Use Classpath Require

Move `resources/acp/index.js` → `src/js/acp/index.js` and `resources/acp/permission.js` → `src/js/acp/permission.js`. Update the CLJS import:

```clojure
(:require ["/js/acp/index" :as acp-factory])
```

Or keep the file next to existing main effects and use a relative require:

```clojure
(:require ["./acp/bridge" :as acp-factory])
```

Delete `"acp": "file:./resources/acp"` from `package.json` and delete `resources/acp/package.json`. The `node_modules/acp` symlink disappears on next `npm install`. Update `test/gremllm/main/effects/acp_test.cljs` to the new require. Update `test/acp-session-history.mjs:9`'s plain-Node `require` path.

### Advantages

- Matches the shadow-cljs docs' explicit recommendation for authored-local JS files.
- Removes the whole pseudo-package fiction: bridge code lives on the classpath with the CLJS that uses it.
- No symlink, no npm alias, no packaging special case.
- No dependency on `:js-provider :require` behavior with remapped resolves.

### Tradeoffs

- Largest diff: two files move, three requires update.
- Bridge directory moves out of `resources/` — a convention change some readers may find unexpected.

## Option B: Add `resources` to the Classpath and Classpath-Require from There

Add `resources` to the shadow-cljs source paths (either via `shadow-cljs.edn :source-paths` or `deps.edn :paths`) and use a classpath require:

```clojure
(:require ["/acp/index" :as acp-factory])
```

Delete `"acp": "file:./resources/acp"` and `resources/acp/package.json`.

### Advantages

- Keeps bridge files where they are.
- Uses the same documented classpath-require pattern as Option A.

### Tradeoffs

- Adding `resources/` to the CLJS classpath pulls everything else in `resources/` (public assets, images) onto the classpath too. Wider scope than needed for a bridge loader.
- Mixes build-output assets and source JS under one source path.
- Not an idiomatic shadow-cljs layout.

## Option C: `:js-options :resolve` Remap (Keep `require("acp")`)

Add to `shadow-cljs.edn`:

```clojure
:js-options {:resolve {"acp" {:target :file :file "resources/acp/index.js"}}}
```

Delete `"acp": "file:./resources/acp"` and `resources/acp/package.json`.

### Advantages

- Preserves `(:require ["acp" :as acp-factory])` unchanged — zero call-site edits.
- Smallest diff if it works.

### Tradeoffs

- Shadow-cljs docs flag `:resolve :target :file` as having **limitations under `:js-provider :require`** (Node.js targets) because "downstream requires escape control." The bridge depends on `@agentclientprotocol/sdk`, `claude-agent-acp/package.json`, and `./permission` — all downstream requires that would escape control.
- Resolution indirection lives in build config, invisible from the call site.

## Option D: Packaging-Side Fix (Keep the `file:` Dependency)

Keep `require("acp")` and fix the packaging so `node_modules/acp` materializes as real files (not a symlink) in the packaged `app.asar`. Candidate mechanisms: `@electron/packager`'s `derefSymlinks` (already defaults to `true` and still fails for us), `asar.unpack` to exclude the bridge from asar, or replacing the `file:` dep with a build-time copy step.

### Advantages

- No CLJS changes.
- Leaves bridge code in `resources/` as-is.

### Tradeoffs

- Root cause is a known-bad interaction between `file:` symlink deps and asar (multiple upstream GitHub issues). Works best case; fragile worst case.
- Adds release-only packaging special cases that are not exercised in dev.
- Still depends on shadow-cljs emitting `require("acp")` and node's module resolution finding it — the thing that already fails.

## Comparison

| Criterion | A: Move to `src/` | B: Classpath `resources/` | C: `:resolve` remap | D: Packaging fix |
|-----------|-------------------|----------------------------|----------------------|-------------------|
| Matches documented shadow-cljs recommendation | Yes (classpath-js) | Partially | No (warned for node-script) | No |
| Addresses verified root cause directly | Yes | Yes | Yes | Indirectly |
| Keeps current `require("acp")` shape | No | No | Yes | Yes |
| Adds packaging complexity | None | None | None | Medium to high |
| Widens CLJS classpath scope | No | Yes (all of `resources/`) | No | No |
| Files changed | 2 moves + 3 requires | 1 config + 1 require | 1 config line | Packaging rules |
| Empirical verification still needed | Low (idiomatic pattern) | Low | High (`:js-provider :require` caveat) | High |

## Decision Criteria

The chosen fix must:

- pass a deterministic packaged-main smoke check against `app.asar/target/main.js`
- preserve ACP loading in dev and test modes
- keep `npm run package` and `npm run make` working
- avoid manual release-only steps
- minimize new packaging special cases

## Working Recommendation

**Preferred: Option A — move the bridge into `src/` and use a classpath require.** This matches the shadow-cljs user guide's explicit recommendation for "local JS files you wrote" (classpath-js). It removes the pseudo-package fiction that caused the problem, uses well-documented semantics, and has no dependency on the `:js-provider :require` edge case that affects Option C.

**Fallback 1: Option B** if keeping the bridge under `resources/` is important to reviewers. Accept the tradeoff that `resources/` becomes a CLJS source path.

**Fallback 2: Option C** only if reviewers strongly want `require("acp")` preserved at call sites. Must be validated empirically against the `:node-script` target — the shadow-cljs docs flag `:resolve :target :file` as having limitations in this provider.

**Last resort: Option D** (packaging-side fix). Same class of problem as the current failure; keeps the symlink fragility on the release path.
