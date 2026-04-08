# S7.2: Popover Positioning Spike — Design

**Date:** 2026-04-09
**Slice:** S7.2 from `docs/plans/2026-02-11-scooter-vertical-slices.md`
**Type:** Learning spike (not shipping UI)
**Depends on:** S7.1 (selection data capture, merged in PR #217)

## Context

S7.2 solves a problem that blocked the previous one-shot branch (`feat/selection-ui-one-shot`). That branch burned four consecutive fix commits cycling through coordinate strategies (panel-relative, absolute, raw viewport, fixed outside panel) without converging. The root cause: stacking browser-level unknowns with application concerns made failures undiagnosable.

This spike isolates the single unknown — reliably positioning a floating element near a text selection inside the scrollable document panel — and solves it with a minimal colored `<div>`.

## Positioning Strategy: `position: absolute` inside `.document-panel`

The popover renders as a child of `.document-panel` (`<section>` with `position: relative` and `overflow-y: auto`). This means:

- `position: absolute` resolves against the panel's padding edge (its nearest positioned ancestor)
- The `transition: flex 0.3s ease` property on `.document-panel` is irrelevant — it only creates a containing-block trap for `position: fixed`, not `position: absolute`
- No CSS changes required; works within the current layout as-is

### Coordinate Conversion

`getClientRects()` returns viewport-relative coordinates. The conversion to panel-relative:

```
popover.top  = lastRect.top  - panelRect.top  + panel.scrollTop + lastRect.height
popover.left = lastRect.left - panelRect.left
```

- **`lastRect`**: Last entry in `client-rects` (already captured by S7.1). Anchors near where the selection ended, not the bounding box center.
- **`panelRect`**: `.document-panel`'s own `getBoundingClientRect()`. New — captured in the placeholder.
- **`panel.scrollTop`**: How far the panel has scrolled. New — captured in the placeholder.
- **`+ lastRect.height`**: Places the popover below the selected text.

This is a pure function: `(last-rect, panel-rect, scroll-top) → {:top :left}`. Testable without a DOM.

## Data Flow

### 1. Extend `:event/text-selection` placeholder

The existing placeholder in `renderer/actions.cljs` captures selection geometry but not panel geometry or mouse coordinates. Add four fields:

| Field | Source | Purpose |
|-------|--------|---------|
| `:mouse-x` | `dom-event.clientX` | Selection direction / natural anchor point |
| `:mouse-y` | `dom-event.clientY` | Selection direction / natural anchor point |
| `:panel-rect` | `.document-panel.getBoundingClientRect()` | Viewport-to-panel conversion |
| `:panel-scroll-top` | `.document-panel.scrollTop` | Scroll offset for absolute positioning |

Panel element found via `(.. dom-event -target (closest ".document-panel"))`.

### 2. Schema changes

Add `:mouse-x`, `:mouse-y` (both `number?`, optional), `:panel-rect` (`ViewportRect`, optional), and `:panel-scroll-top` (`number?`, optional) to `CapturedSelection` in `schema.cljs`. Optional because these fields are not present in existing S7.1 test fixtures.

Update `captured-selection-from-dom` coercer in `schema/codec.cljs` to pass through the new fields.

### 3. State

New state path `[:excerpt :popover]` stores `{:top :left}` (panel-relative coords) or `nil` when dismissed.

Add to `renderer/state/excerpt.cljs`:
- `popover-path` — `[:excerpt :popover]`
- `get-popover` — accessor

### 4. Actions

In `renderer/actions/excerpt.cljs`:

- **`compute-popover-position`**: Pure function. Takes state, reads captured selection data, computes panel-relative coords using the formula above. Returns `[:effects/save popover-path {:top N :left N}]`.
- **`dismiss-popover`**: Returns `[:effects/save popover-path nil]`.

The `capture` action already saves selection data. It returns both effects: save the captured data, then dispatch `compute-popover-position`. When selection is collapsed (nil data), it dispatches `dismiss-popover` instead.

### 5. UI Rendering

The popover renders at the `ui.cljs` level, inside `[e/document-panel ...]`, as a sibling of the document `article`:

```clojure
[e/document-panel
  (when nav-expanded? ...)
  (document-ui/render-document document-content pending-diffs)
  (when popover-pos
    [:div {:style {:position "absolute"
                   :top      (str (:top popover-pos) "px")
                   :left     (str (:left popover-pos) "px")
                   :z-index  5}}
     "Stage"])]
```

Spike renders a small colored box with text. No styled popover component — that's S7.4's job.

### 6. Dismiss Behavior

Three triggers clear the popover:

| Trigger | Mechanism |
|---------|-----------|
| Scroll on `.document-panel` | `scroll` event listener dispatches `dismiss-popover` |
| Click outside popover | `mousedown` on document panel (not on popover) dispatches `dismiss-popover` |
| New selection | `capture` action replaces the popover position (or clears it if selection is collapsed) |

For the spike, scroll-dismiss is the critical one to verify.

## Files Modified

| File | Change |
|------|--------|
| `src/gremllm/renderer/actions.cljs` | Extend `:event/text-selection` placeholder with `mouse-x`, `mouse-y`, `panel-rect`, `panel-scroll-top` |
| `src/gremllm/schema.cljs` | Add four optional fields to `CapturedSelection` |
| `src/gremllm/schema/codec.cljs` | Update `captured-selection-from-dom` coercer for new fields |
| `src/gremllm/renderer/state/excerpt.cljs` | Add `popover-path` and `get-popover` |
| `src/gremllm/renderer/actions/excerpt.cljs` | Add `compute-popover-position` and `dismiss-popover` |
| `src/gremllm/renderer/ui.cljs` | Read popover state, render positioned div inside document-panel |
| `src/gremllm/renderer/ui/document.cljs` | Add scroll listener for dismiss |

No new files.

## Verification

Manual testing (this is a spike — the testable result is visual):

1. Start the app with `npm run dev`
2. Open a workspace with a `document.md` containing several paragraphs
3. **Single-line selection:** Select a word or phrase. Colored box appears directly below the selection, aligned with where the selection ends.
4. **Multi-line selection:** Select text spanning multiple lines. Box appears below the last line of the selection.
5. **Scroll test:** Select text, see the box. Scroll the document panel. Box dismisses.
6. **Resize test:** Select text, see the box. Resize the window (flex transition fires). Box remains correctly positioned (or dismisses — both acceptable for the spike).
7. **Inspect in Dataspex:** Check `[:excerpt :captured]` shows `panel-rect`, `panel-scroll-top`, `mouse-x`, `mouse-y`. Check `[:excerpt :popover]` shows computed `{:top :left}`.

Unit test for the pure coordinate conversion function (the only logic worth testing).
