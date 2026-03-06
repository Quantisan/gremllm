# diff-match-patch: Replace Custom Anchoring with Patch Apply

## Problem

The current anchoring and sequential diff composition code reinvents what `@sanity/diff-match-patch` already provides:

- `anchoring.cljs` uses exact `String.indexOf` to locate `old-text` in the document. No fuzzy matching. Fails if the AI's `old-text` has minor whitespace differences from what's in the document.
- `compose-sequential-diffs` is not yet implemented. The problem: diff 2 may reference content that only exists after diff 1 is applied, so you must anchor each diff against the evolving document state.

## Spike Findings (2026-03-05)

Status: **No-go for naive drop-in; go with compensating strategies.**

Evidence from `node test/diff_match_patch_spike.mjs` (real logs only):

1. **No constructor (non-blocking).** `@sanity/diff-match-patch` does not expose `new diff_match_patch()`; it exposes functional APIs (`makePatches`, `applyPatches`, `match`, etc.). The proposal pseudocode was already noted as outdated. Rewrite against the functional imports.
2. **Snippet patching fails on long docs (significant).** `makePatches(oldText, newText)` applied to the full document fails when the true match is far from the patch's expected location. Guide Option 1 (snippet-only patching) is blocked. Requires guide Option 2: location-seeded patching using the existing `:locations[].line` data.
3. **Offset recovery works (confirms guide Option 2).** Manually seeding patch coordinates from `locations[].line` recovered the long-document case. The spike's offset experiment succeeded, validating that this approach is viable.
4. **Overlapping sequential diffs (significant).** Not solvable by naive sequential apply—applying one diff can invalidate the next regardless of order. Needs one of: recompute each diff against latest document state, merge diffs before applying, or surface as a conflict (guide Deep Dive B, options 2–4).
5. **Multi-paragraph diffs work (non-blocking).** Multi-paragraph diffs applied successfully in the captured fixture. The library handles this case.

Decision: the spike blocks the original naive proposal but validates that `@sanity/diff-match-patch` is viable. Both significant findings have documented strategies in `context/diff-match-patch.md`.

## The Library: `@sanity/diff-match-patch`

npm: `@sanity/diff-match-patch`

Single-file, zero-dependency JS library. Battle-tested (Google Docs, CodeMirror, etc.). The relevant APIs:

```js
import { makePatches, applyPatches } from "@sanity/diff-match-patch";

// Create a patch from old → new
const patches = makePatches(oldText, newText);

// Apply patch to content — finds old_text with fuzzy matching
const [newContent, appliedFlags] = applyPatches(patches, content);
// applied_flags[i] === true means patch i was successfully applied

// patches[i].start1 is not guaranteed to be a full-document anchor when built from snippet text
patches[0].start1;
```

`applyPatches` uses fuzzy matching internally, but in practice this does not remove the need for location context in long documents.

## Proposed Simplification

This section reflects the original proposal direction. The spike findings above block direct adoption of this design without compensating logic.

### Status model

Reduce from `:anchored / :unmatched / :ambiguous` to `:anchored / :failed`.

The ambiguous case (multiple matches) was a symptom of using exact `indexOf`. With fuzzy patch application, `diff-match-patch` picks the best match by proximity — no ambiguity to report.

### anchoring.cljs → replace entirely

Current: manual `indexOf` loop returning `:anchored / :unmatched / :ambiguous`.

New: call `patch_make` + `patch_apply`, check `applied_flags`:

```clojure
;; NOTE: This pseudocode is outdated and not directly implementable against
;; @sanity/diff-match-patch, which uses functional exports rather than
;; js/diff_match_patch constructor methods.
```

### compose-sequential-diffs → apply patches in sequence

```clojure
;; NOTE: Sequential composition remains unresolved for overlap cases. Captured
;; real logs show second patch failure after first patch application in both
;; orders for overlapping pairs.
```

Note: for rendering we anchor against the *original* content (to show what changed), but apply sequentially to resolve ambiguity. The `char-index` from the final evolved state maps back to original via offset tracking if needed — this may need further thought.

### compose-diff-segments → keep as-is

The segment-splitting loop is clean domain logic. No library needed.

## What to Verify Before Implementing

1. Does `patch_apply` return `start1` reflecting position in the *original* content string, or in the already-patched string? Also verify whether `patches[0].start1` is snippet-relative vs full-document-relative for `patch_make(old-text, new-text)`.
2. For sequential diffs that overlap, does `compose-sequential-diffs` need to track cumulative offset shifts to map final positions back to original coordinates? The current test expects a single merged diff-block for overlapping sequential edits — the implementation strategy for this needs fleshing out.
3. Check if `diff-match-patch` handles multi-paragraph `old-text` correctly (no degradation from the current exact-match behavior for unambiguous cases).

## Files Affected

- `test/diff_match_patch_spike.mjs` — reproducible spike runner for real-log experiments
- `docs/plans/2026-03-05-diff-match-patch-spike-design.md` — design and decision criteria
- `src/gremllm/renderer/ui/document/anchoring.cljs` — replace core logic
- `src/gremllm/renderer/ui/document/composition.cljs` — implement `compose-sequential-diffs`
- `test/gremllm/renderer/ui/document/anchoring_test.cljs` — remove `:ambiguous` test cases
- `test/gremllm/renderer/ui/document/composition_test.cljs` — no structural changes needed
- `package.json` — add `diff-match-patch` dependency

## Revised Next Steps (Before Any Production Replacement)

1. Define a production anchor strategy for long documents:
   - Use explicit location hints (`locations[].line`) as primary search seed, or
   - Build full-document patches from `original` and replacement text when available.
2. Define overlap handling policy for sequential diffs:
   - Merge by transformed coordinate ranges, or
   - Normalize to a single consolidated patch before rendering.
3. Re-run the spike script with any new strategy and require `decision = go` before touching renderer production logic.
