# Electron selection capture research for S7.1

Date: 2026-03-11
Scope: Research the latest official Electron documentation relevant to `getSelection()` and identify other suitable selection-extraction functions for `S7.1: Selection data capture spike` in `docs/plans/2026-02-11-scooter-vertical-slices.md`.

## Executive Summary

Electron does not provide a dedicated app-level replacement for `getSelection()` in the normal renderer text-selection flow. For S7.1, the most capable and appropriate approach remains Chromium's renderer-side Selection API:

1. `document.getSelection()` or `window.getSelection()`
2. `selection.getRangeAt(0)`
3. `range.getBoundingClientRect()`
4. `range.getClientRects()`
5. Selection and Range node/offset fields for structural inspection

Electron does expose selection-related metadata through `webContents`, but those APIs are weaker for this spike because they are context-menu-oriented or require crossing process boundaries.

## Findings

### 1. `getSelection()` is still the primary API

Electron's official docs do not document a separate Electron-specific `getSelection()` API. In practice this means selection capture in the renderer should use the standard DOM Selection API exposed by Chromium.

For S7.1, the useful fields are:

- `selection.toString()`
- `selection.anchorNode`
- `selection.anchorOffset`
- `selection.focusNode`
- `selection.focusOffset`
- `selection.isCollapsed`
- `selection.rangeCount`
- `selection.getRangeAt(0)`

From the first `Range`, the useful fields are:

- `range.startContainer`
- `range.startOffset`
- `range.endContainer`
- `range.endOffset`
- `range.getBoundingClientRect()`
- `range.getClientRects()`

### 2. `range.getClientRects()` is the most useful companion API

`getBoundingClientRect()` gives one union rectangle. That is enough for an initial spike payload, but it can hide important geometry when a selection spans multiple wrapped lines or block boundaries.

`getClientRects()` is a better fit for inspection because it exposes the per-fragment rectangles that explain how the browser actually laid out the selection.

Implication for S7.1:

- capture both the union rect and the fragment rects
- inspect fragment behavior before committing to S7.2 popover positioning assumptions

### 3. `selectionchange` is the best companion event

The current plan names a `mouseup` handler on the document panel. That is still the right first trigger for S7.1, but the browser also provides `document`-level `selectionchange`.

This is useful for:

- keyboard-driven selection
- detecting collapse or replacement of the current selection
- clearing stale selection state without waiting for another mouse event

Important constraint:

- `selectionchange` is attached at `document`; it does not bubble from the panel element

### 4. Electron `webContents` exposes selection metadata, but only opportunistically

Electron's `webContents` docs expose selection-related data on the `'context-menu'` event parameters, including:

- `selectionText`
- `selectionRect`
- `selectionStartOffset`

This is useful as a reference point, but it is not a good primary mechanism for S7.1 because:

- it fires when the context menu is invoked, not on every normal selection
- it lives in main-process `webContents` event handling, while S7.1 is renderer-local UI state
- the docs do not define `selectionStartOffset` precisely enough to treat it as markdown source anchoring data

That last point is an inference from the docs: the event documents the field, but not with enough semantic detail for Gremllm's source-offset needs.

### 5. Process-crossing JavaScript execution is available, but unnecessary here

Electron officially documents JavaScript execution surfaces such as:

- `webContents.executeJavaScript(...)`
- `webFrame.executeJavaScript(...)`
- `webFrameMain.executeJavaScript(...)`

These can be used to query selection state indirectly, but they are not a better fit for S7.1 because:

- the document panel already lives in the renderer
- the spike wants to inspect raw browser selection behavior directly
- crossing the preload or main boundary would add complexity without improving selection fidelity

## Suitability ranking for S7.1

### Best fit

1. `document.getSelection()` or `window.getSelection()`
2. `selection.getRangeAt(0)`
3. `range.getBoundingClientRect()`
4. `range.getClientRects()`
5. `document` `selectionchange`

### Situational only

1. `HTMLInputElement.selectionStart` / `selectionEnd`
2. `HTMLTextAreaElement.selectionStart` / `selectionEnd`

These are only relevant if selection capture later expands into editable inputs. They do not apply to the rendered markdown document panel.

### Not recommended as primary S7.1 mechanism

1. `webContents` `'context-menu'` event params: `selectionText`, `selectionRect`, `selectionStartOffset`
2. `webContents.executeJavaScript(...)`
3. `webFrame.executeJavaScript(...)`
4. `webFrameMain.executeJavaScript(...)`

## Recommended capture payload for the spike

For `[:staging :raw-selection]`, the spike should capture at least:

```clojure
{:text             ...
 :is-collapsed     ...
 :range-count      ...
 :anchor-node      ...
 :anchor-offset    ...
 :focus-node       ...
 :focus-offset     ...
 :start-container  ...
 :start-offset     ...
 :end-container    ...
 :end-offset       ...
 :bounding-rect    {:top ... :left ... :right ... :bottom ... :width ... :height ...}
 :client-rects     [{:top ... :left ... :right ... :bottom ... :width ... :height ...}]}
```

The node fields will need inspection-friendly serialization for Dataspex because DOM nodes themselves are not useful as raw opaque objects in app state.

## Recommendation

For `S7.1: Selection data capture spike`, keep the implementation entirely in the renderer and use the standard Selection and Range APIs. Specifically:

1. capture on panel `mouseup`
2. read `document.getSelection()`
3. if `rangeCount > 0`, read `getRangeAt(0)`
4. store text, node/offset metadata, union rect, and fragment rects
5. optionally add `document` `selectionchange` for cleanup and keyboard coverage

Do not route initial capture through Electron main-process APIs. The Electron-native alternatives are less complete for this spike than the renderer's Selection API.

## Sources

- Electron `webContents`: https://www.electronjs.org/docs/latest/api/web-contents
- Electron `webFrame`: https://www.electronjs.org/docs/latest/api/web-frame
- Electron `webFrameMain`: https://www.electronjs.org/docs/latest/api/web-frame-main
- MDN `Document.getSelection()`: https://developer.mozilla.org/en-US/docs/Web/API/Document/getSelection
- MDN `Window.getSelection()`: https://developer.mozilla.org/en-US/docs/Web/API/Window/getSelection
- MDN `Selection`: https://developer.mozilla.org/en-US/docs/Web/API/Selection
- MDN `Selection.getRangeAt()`: https://developer.mozilla.org/en-US/docs/Web/API/Selection/getRangeAt
- MDN `Range.getBoundingClientRect()`: https://developer.mozilla.org/en-US/docs/Web/API/Range/getBoundingClientRect
- MDN `Range.getClientRects()`: https://developer.mozilla.org/en-US/docs/Web/API/Range/getClientRects
- MDN `Document.selectionchange`: https://developer.mozilla.org/en-US/docs/Web/API/Document/selectionchange_event
