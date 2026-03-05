# diff-match-patch: Replace Custom Anchoring with Patch Apply

## Problem

The current anchoring and sequential diff composition code reinvents what `@sanity/diff-match-patch` already provides:

- `anchoring.cljs` uses exact `String.indexOf` to locate `old-text` in the document. No fuzzy matching. Fails if the AI's `old-text` has minor whitespace differences from what's in the document.
- `compose-sequential-diffs` is not yet implemented. The problem: diff 2 may reference content that only exists after diff 1 is applied, so you must anchor each diff against the evolving document state.

## The Library: `@sanity/diff-match-patch`

npm: `@sanity/diff-match-patch`

Single-file, zero-dependency JS library. Battle-tested (Google Docs, CodeMirror, etc.). The relevant APIs:

```js
const dmp = new diff_match_patch();

// Create a patch from old → new
const patches = dmp.patch_make(old_text, new_text);

// Apply patch to content — finds old_text with fuzzy matching
const [new_content, applied_flags] = dmp.patch_apply(patches, content);
// applied_flags[i] === true means patch i was successfully applied

// Each patch has .start1, but watch out: this may be relative to patch text
// construction context, not the full document anchor position.
patches[0].start1
```

`patch_apply` uses Bitap (fuzzy) matching internally, so it tolerates minor discrepancies between the AI's `old-text` and the actual document content.

## Proposed Simplification

### Status model

Reduce from `:anchored / :unmatched / :ambiguous` to `:anchored / :failed`.

The ambiguous case (multiple matches) was a symptom of using exact `indexOf`. With fuzzy patch application, `diff-match-patch` picks the best match by proximity — no ambiguity to report.

### anchoring.cljs → replace entirely

Current: manual `indexOf` loop returning `:anchored / :unmatched / :ambiguous`.

New: call `patch_make` + `patch_apply`, check `applied_flags`:

```clojure
(defn anchor-diff [content {:keys [old-text new-text] :as diff}]
  (if (or (nil? old-text) (empty? old-text))
    (assoc diff :anchor-status :failed)
    (let [dmp     (js/diff_match_patch.)
          patches (.patch_make dmp old-text new-text)
          [_ flags] (.patch_apply dmp patches content)
          applied (aget flags 0)]
      (if applied
        (assoc diff
               :anchor-status :anchored
               :char-index    (.-start1 (aget patches 0))
               :length        (count old-text))
        (assoc diff :anchor-status :failed)))))
```

### compose-sequential-diffs → apply patches in sequence

```clojure
(defn compose-sequential-diffs [content diffs]
  (let [dmp      (js/diff_match_patch.)
        evolving (reduce (fn [c {:keys [old-text new-text]}]
                           (let [patches (.patch_make dmp old-text new-text)
                                 [c' _]  (.patch_apply dmp patches c)]
                             c'))
                         content
                         diffs)
        ;; anchor all diffs against the final evolved content, then compose
        anchored (anchor-diffs evolving diffs)]
    (compose-diff-segments content anchored)))
```

Note: for rendering we anchor against the *original* content (to show what changed), but apply sequentially to resolve ambiguity. The `char-index` from the final evolved state maps back to original via offset tracking if needed — this may need further thought.

### compose-diff-segments → keep as-is

The segment-splitting loop is clean domain logic. No library needed.

## What to Verify Before Implementing

1. Does `patch_apply` return `start1` reflecting position in the *original* content string, or in the already-patched string? Also verify whether `patches[0].start1` is snippet-relative vs full-document-relative for `patch_make(old-text, new-text)`.
2. For sequential diffs that overlap, does `compose-sequential-diffs` need to track cumulative offset shifts to map final positions back to original coordinates? The current test expects a single merged diff-block for overlapping sequential edits — the implementation strategy for this needs fleshing out.
3. Check if `diff-match-patch` handles multi-paragraph `old-text` correctly (no degradation from the current exact-match behavior for unambiguous cases).

## Files Affected

- `src/gremllm/renderer/ui/document/anchoring.cljs` — replace core logic
- `src/gremllm/renderer/ui/document/composition.cljs` — implement `compose-sequential-diffs`
- `test/gremllm/renderer/ui/document/anchoring_test.cljs` — remove `:ambiguous` test cases
- `test/gremllm/renderer/ui/document/composition_test.cljs` — no structural changes needed
- `package.json` — add `diff-match-patch` dependency
