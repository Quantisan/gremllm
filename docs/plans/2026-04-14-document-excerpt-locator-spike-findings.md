# Document Excerpt Locator Spike Findings

**Date:** 2026-04-14
**Fixture:** `resources/gremllm-launch-log.md`

## Questions

1. Does one enclosing block give enough precision for normal selections?
2. What do we get for cross-block selections?
3. Are start/end offsets reliable inside a single rendered block?
4. Which fields are useful enough to carry into `DocumentExcerpt.locator`?
5. Does the DOM/block count ever mismatch (watch for the `[excerpt-locator-spike] DOM/block count mismatch` warning)?
6. Is block-level tokenisation (markdown-it) the right layer, or would `nextjournal.markdown/parse` (richer EDN AST) be more Clojury and sufficient?

## Cases

### Single paragraph

- Console output:
```json
{
  "selection-direction": "forward",
  "anchor-node": "#text",
  "anchor-offset": 5,
  "focus-node": "#text",
  "focus-offset": 28,
  "common-ancestor": "#text",
  "locator": {
    "block-kind": "paragraph",
    "block-index": 2,
    "block-start-line": 3,
    "block-end-line": 3,
    "start-offset": 43,
    "end-offset": 66
  }
}
```
- Observations:
  - Same-block paragraph selection produced stable block metadata plus rendered-text offsets.
  - Offsets were measured against normalized rendered paragraph text, not raw markdown source positions.
  - No DOM/block mismatch warning appeared.

### Mixed formatting inside one paragraph

- Console output:
```json
{
  "selection-direction": "forward",
  "anchor-node": "#text",
  "anchor-offset": 4,
  "focus-node": "#text",
  "focus-offset": 11,
  "common-ancestor": "P",
  "locator": {
    "block-kind": "paragraph",
    "block-index": 2,
    "block-start-line": 3,
    "block-end-line": 3,
    "start-offset": 4,
    "end-offset": 38
  }
}
```
- Observations:
  - A selection spanning bold and italic inline markup still resolved to a single paragraph block with usable offsets.
  - `common-ancestor` moved up to `P`, but locator fidelity remained good.
  - This is the strongest evidence that block-relative offsets can survive inline formatting boundaries.

### Cross-block selection

- Console output:
```json
{
  "selection-direction": "forward",
  "anchor-node": "#text",
  "anchor-offset": 0,
  "focus-node": "#text",
  "focus-offset": 1,
  "common-ancestor": "DIV",
  "locator": {
    "block-kind": "heading",
    "block-index": 1,
    "block-start-line": 1,
    "block-end-line": 1
  }
}
```
- Observations:
  - Cross-block capture kept the start block as primary and intentionally omitted offsets.
  - Rendered selection text collapsed the visual gap between blocks into a single newline: `Gremllm Launch Log\nOur Gremllm `.
  - This shape is usable as coarse provenance but not precise enough for exact re-anchoring by itself.

### List item

- Console output:
```json
{
  "selection-direction": "forward",
  "anchor-node": "#text",
  "anchor-offset": 6,
  "focus-node": "#text",
  "focus-offset": 25,
  "common-ancestor": "#text",
  "locator": {
    "block-kind": "list-item",
    "block-index": 4,
    "block-start-line": 6,
    "block-end-line": 6,
    "start-offset": 6,
    "end-offset": 25
  }
}
```
- Observations:
  - List items resolved cleanly once the extractor preferred inline-token spans over raw `list_item_open` spans.
  - The corrected line span matched the markdown source line rather than the trailing blank line.

### Code block

- Console output:
```json
{
  "selection-direction": "forward",
  "anchor-node": "#text",
  "anchor-offset": 0,
  "focus-node": "#text",
  "focus-offset": 15,
  "common-ancestor": "#text",
  "locator": {
    "block-kind": "code-block",
    "block-index": 9,
    "block-start-line": 13,
    "block-end-line": 15,
    "start-offset": 0,
    "end-offset": 15
  }
}
```
- Observations:
  - Fenced code blocks produced precise block spans and straightforward offsets against the rendered code text.
  - The current approach treats the whole fenced block as one block, which is likely sufficient unless line-level code anchoring becomes a requirement.

### Table cell (if available)

- Console output:
  - Table not present in `resources/gremllm-launch-log.md`.
- Observations:
  - No evidence collected for table-cell behavior in this spike.

## Recommendation For Spec Draft

- Keep:
  - `block-kind`
  - `block-index`
  - `block-start-line`
  - `block-end-line`
  - `start-offset` and `end-offset` only when the selection stays within one rendered block
- Drop:
  - `selection-direction`
  - `anchor-node`
  - `anchor-offset`
  - `focus-node`
  - `focus-offset`
  - `common-ancestor`
  - These were useful for debugging the spike but look too DOM-specific and unstable for the persisted locator spec.
- Open questions:
  - Is blanket-clearing all topics' staged-selections on `document.actions/set-content` the right invalidation strategy, or should we key invalidation off content hash / document version?
  - Do we want locator offsets to be defined against rendered block text, normalized markdown text, or both?
  - If cross-block excerpts are first-class, do we need an explicit end block in the eventual locator schema?

## Next Steps (cleanup after findings captured)

Remove before merging to main:
- `[excerpt-locator-spike]` console-log effect calls in `excerpt/capture`
- `:ui.effects/console-log` registration (unless it proves generally useful)
- `locator-debug-path` state path and all `[:effects/save ... locator-debug-path ...]` effects
- `:locator-debug` key from `:event/text-selection` placeholder output
- `data-grem-block-*` attribute writes in `sync-block-metadata!` (or promote to non-spike if useful)
- `selection-debug-from-dom` if locator is not being kept

## Summary Answers

1. One enclosing block is enough precision for normal same-block selections in this fixture.
2. Cross-block selections degrade to coarse start-block provenance and lose exact offsets.
3. Start/end offsets were reliable for paragraph, mixed-format paragraph, list-item, and code-block cases in the rendered DOM.
4. The fields worth carrying forward are block identity plus optional same-block offsets.
5. No `[excerpt-locator-spike] DOM/block count mismatch` warning appeared during the spike.
6. `markdown-it` block tokenisation was sufficient for this evidence pass. `nextjournal.markdown/parse` may still be worth evaluating later for a more idiomatic AST, but it is not required to answer the current locator questions.
