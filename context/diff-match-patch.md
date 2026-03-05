# @sanity/diff-match-patch (v3.2.0) Guide

## Purpose and Scope

This guide is a generic reference for `@sanity/diff-match-patch` version `3.2.0`.

Constraints for this document:
- Generic upstream reference only
- No project-specific observations
- JS/TS examples only
- Neutral options, no recommendations

## Version Snapshot

- Package: `@sanity/diff-match-patch`
- Version in scope: `3.2.0` (released `2025-01-22`)
- npm dist tag at time of writing: `latest -> 3.2.0`
- Runtime: `node >= 18.18` (from package `engines`)
- Module formats: ESM + CommonJS (`exports` maps both)
- API style: functional exports (`makePatches`, `applyPatches`, `match`, etc.), not class-constructor API

Notable `3.2.0` additions:
- `xIndex` is exported
- `match()` accepts options (`threshold`, `distance`)

## API Map and Quick Lookup

| Domain | Export(s) | Use for |
|---|---|---|
| Diff | `makeDiff`, `cleanupSemantic`, `cleanupEfficiency`, `DIFF_DELETE`, `DIFF_INSERT`, `DIFF_EQUAL` | Building and normalizing diff tuples |
| Match | `match`, `MatchOptions` | Fuzzy search for best location near an expected index |
| Patch | `makePatches`, `applyPatches`, `parsePatch`, `stringifyPatches`, `stringifyPatch`, `Patch`, `PatchResult` | Creating, serializing, parsing, and applying patches |
| Index/Unicode utils | `xIndex`, `adjustIndiciesToUcs2` | Index mapping and UTF-8/UCS-2 index adjustment |

Quick lookup:
- Need text changes as operations: `makeDiff(a, b)`
- Need patch objects from two strings: `makePatches(a, b)`
- Need to apply patch objects: `applyPatches(patches, text)`
- Need patch text format round trip: `stringifyPatches(patches)` + `parsePatch(textPatch)`
- Need fuzzy locate only: `match(text, pattern, nearIndex, opts)`
- Need map a source index through a diff: `xIndex(diffs, location)`

## Core Data Model

### Diff

`Diff` is a tuple: `[DiffType, string]`

- `DIFF_DELETE = -1`
- `DIFF_EQUAL = 0`
- `DIFF_INSERT = 1`

Example:

```ts
import {makeDiff, DIFF_DELETE, DIFF_INSERT, DIFF_EQUAL} from '@sanity/diff-match-patch'

const diffs = makeDiff('from this', 'to this')
// Example shape:
// [
//   [DIFF_DELETE, 'fr'],
//   [DIFF_INSERT, 't'],
//   [DIFF_EQUAL, 'o'],
//   ...
// ]
```

### Patch

`Patch` is an object with diff operations plus index/length metadata:

```ts
type Patch = {
  diffs: [number, string][]
  start1: number
  start2: number
  utf8Start1: number
  utf8Start2: number
  length1: number
  length2: number
  utf8Length1: number
  utf8Length2: number
}
```

`start*`/`length*` and `utf8Start*`/`utf8Length*` are covered in the Unicode section.

### PatchResult

`applyPatches()` returns a tuple:

```ts
type PatchResult = [string, boolean[]]
```

- index `0`: patched text
- index `1`: apply status per patch chunk (`true`/`false`)

Example:

```ts
import {makePatches, applyPatches} from '@sanity/diff-match-patch'

const patches = makePatches('from this', 'to this')
const [nextText, applied] = applyPatches(patches, 'from this')
// nextText: 'to this'
// applied: [true] // one entry per patch
```

## Deep Dive A: Long-Document Anchoring

## Deep Dive B: Sequential and Overlapping Diffs

## Unicode and Index Semantics

## Serialization and Replay

## Match Tuning

## Gotchas and Verification Checklist

## Progressive-Disclosure References
