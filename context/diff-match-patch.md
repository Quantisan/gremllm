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

Long-document anchoring is about where patch application starts searching.

`applyPatches()` does fuzzy matching internally, but it still uses expected locations from each patch object (`start2` plus internal delta tracking). Depending on how patches are generated, those expected locations may be more or less useful for large documents.

### Option 1: Snippet-Only Patching

Create patch objects from `oldText` and `newText` only, then apply to a larger document.

```ts
import {makePatches, applyPatches} from '@sanity/diff-match-patch'

const patches = makePatches(oldText, newText)
const [nextDoc, applied] = applyPatches(patches, fullDocument)
```

Trade-offs:
- Pros: simplest flow and smallest input surface
- Cons: expected patch positions come from snippet context, not necessarily full-document positions

### Option 2: Location-Seeded Snippet Patching

Use an external location hint to find a likely anchor near a target index, then offset patch coordinates before apply.

```ts
import {makePatches, applyPatches, match, type Patch} from '@sanity/diff-match-patch'

function offsetPatches(patches: Patch[], charOffset: number): Patch[] {
  return patches.map((p) => ({
    ...p,
    start1: p.start1 + charOffset,
    start2: p.start2 + charOffset,
    utf8Start1: p.utf8Start1 + charOffset,
    utf8Start2: p.utf8Start2 + charOffset,
  }))
}

const snippetPatches = makePatches(oldText, newText)
const guessedIndex = lineHintIndex // or any external hint
const bestIndex = match(fullDocument, oldText.slice(0, 32), guessedIndex, {
  distance: 1000,
  threshold: 0.5,
})

const seeded = bestIndex >= 0 ? offsetPatches(snippetPatches, bestIndex) : snippetPatches
const [nextDoc, applied] = applyPatches(seeded, fullDocument)
```

Trade-offs:
- Pros: keeps snippet-based workflow while adding a controllable search seed
- Cons: requires external hinting strategy and careful offset handling

### Option 3: Full-Document Patch Generation

Generate patches from full source and full target text.

```ts
import {makePatches, applyPatches} from '@sanity/diff-match-patch'

const nextDocument = fullDocument.replace(oldText, newText) // example transform
const fullPatches = makePatches(fullDocument, nextDocument)
const [appliedDocument, applied] = applyPatches(fullPatches, fullDocument)
```

Trade-offs:
- Pros: patch coordinates are derived from full-document context
- Cons: requires access to both full source and full target states

### Option 4: Hybrid Fallback Flow

Try simpler anchoring first, then escalate to heavier anchoring when apply flags fail.

```ts
import {makePatches, applyPatches} from '@sanity/diff-match-patch'

const firstTry = makePatches(oldText, newText)
const [firstDoc, firstApplied] = applyPatches(firstTry, fullDocument)

const allApplied = firstApplied.length > 0 && firstApplied.every(Boolean)
if (!allApplied) {
  // Fallback strategy can be location-seeded snippet patching
  // or full-document patch generation, depending on system constraints.
}
```

Trade-offs:
- Pros: allows staged complexity
- Cons: introduces branching behavior and more states to test

### Notes for Option Selection

- Check `boolean[]` apply flags, not just resulting text.
- If you mutate patch coordinates directly, keep character and UTF-8 fields consistent.
- Keep examples aligned to functional exports (`makePatches`, `applyPatches`, `match`).

## Deep Dive B: Sequential and Overlapping Diffs

Sequential diffs are usually applied against evolving text state:

1. apply patch set A to base text
2. use the result as input when applying patch set B

This is different from applying both sets independently to the original text.

### Sequential Apply Model

```ts
import {applyPatches, type Patch} from '@sanity/diff-match-patch'

type ApplyStep = {
  text: string
  applied: boolean[]
}

function applySequence(base: string, patchSets: Patch[][]): ApplyStep[] {
  const steps: ApplyStep[] = []
  let current = base

  for (const patches of patchSets) {
    const [next, applied] = applyPatches(patches, current)
    steps.push({text: next, applied})
    current = next
  }

  return steps
}
```

Interpretation notes:
- each `applied` entry maps to one patch chunk in that apply call
- partial success is possible (`[true, false, ...]`)
- a later sequence step may fail even when earlier steps succeeded

### Overlap Scenarios

Overlaps happen when two patch sets target the same or nearby regions. In that case, applying one patch set can invalidate assumptions used by another patch set.

Neutral handling options:

1. Keep patch sets independent and accept failed apply flags
- Useful when best-effort behavior is acceptable

2. Recompute later patches against the latest successful state
- Generate new patches from updated text after each successful step

3. Merge or transform overlapping edits before patch apply
- Resolve overlap semantics first, then apply a consolidated patch set

4. Surface overlap as a conflict state
- Skip automatic merge and escalate to conflict-resolution logic

### Compact Success/Failure Inspection Pattern

```ts
import {applyPatches, type Patch} from '@sanity/diff-match-patch'

function applyAndInspect(text: string, patches: Patch[]) {
  const [next, flags] = applyPatches(patches, text)
  const successCount = flags.filter(Boolean).length
  const failedCount = flags.length - successCount
  return {next, flags, successCount, failedCount}
}
```

### Practical Notes

- If sequence order matters, test both single-step and end-to-end outcomes.
- Consider treating any `false` in `boolean[]` as a first-class event for metrics or retries.
- Keep patch generation/apply boundaries explicit in logs to debug overlap behavior.

## Unicode and Index Semantics

## Serialization and Replay

## Match Tuning

## Gotchas and Verification Checklist

## Progressive-Disclosure References
