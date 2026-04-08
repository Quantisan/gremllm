# S7.2: Popover Positioning Spike — Implementation Plan

## Context

S7.2 resolves the exact failure point of the `feat/selection-ui-one-shot` branch: reliably positioning a floating element near a text selection inside the scrollable document panel. The previous branch cycled through four coordinate strategies without converging because browser-level unknowns were stacked with application concerns.

This spike isolates the positioning problem. The testable result is a small colored `<div>` appearing below a text selection, correctly positioned on scroll and resize. Design spec: `docs/superpowers/specs/2026-04-09-s7-2-popover-positioning-design.md`.

**Strategy:** `position: absolute` inside `.document-panel` (which has `position: relative`). Avoids the `transition: flex` containing-block trap that broke `position: fixed` approaches.

## Steps

### Step 1: Extend the `:event/text-selection` placeholder with panel geometry and mouse coords

**Files:**
- `src/gremllm/renderer/actions.cljs` (lines 82–93)

**Changes:**
- Destructure `{:replicant/keys [dom-event]}` instead of `_`
- Find panel via `(.. dom-event -target (closest ".document-panel"))`
- Add four fields to the returned map:
  - `:mouse-x` — `(.-clientX dom-event)`
  - `:mouse-y` — `(.-clientY dom-event)`
  - `:panel-rect` — `(rect-from-dom (.getBoundingClientRect panel))`
  - `:panel-scroll-top` — `(.-scrollTop panel)`

**Verify:** `npm run test` — existing tests should still pass (they don't exercise the placeholder directly).

### Step 2: Update schema and codec for new fields

**Files:**
- `src/gremllm/schema.cljs` (lines 198–208) — `CapturedSelection`
- `src/gremllm/schema/codec.cljs` (lines 111–114) — `captured-selection-from-dom`

**Schema changes:** Add four optional fields to `CapturedSelection`:
```clojure
[:mouse-x {:optional true} number?]
[:mouse-y {:optional true} number?]
[:panel-rect {:optional true} ViewportRect]
[:panel-scroll-top {:optional true} number?]
```

Optional so existing test fixtures (`schema_test/single-word-selection` etc.) remain valid without modification.

**Codec:** No change needed — `m/coerce` with `json-transformer` handles optional fields transparently.

**Verify:** `npm run test` — existing schema and codec tests pass unchanged.

### Step 3: Add popover state path and accessor

**File:** `src/gremllm/renderer/state/excerpt.cljs`

**Add:**
```clojure
(def popover-path [:excerpt :popover])

(defn get-popover [state]
  (get-in state popover-path))
```

### Step 4: Add `compute-popover-position` and `dismiss-popover` actions (TDD)

**Files:**
- `test/gremllm/renderer/actions/excerpt_test.cljs` — tests first
- `src/gremllm/renderer/actions/excerpt.cljs` — implementation

**Test cases for `compute-popover-position`:**
1. With valid captured data (single client-rect): returns `[:effects/save popover-path {:top N :left N}]` with correct math
2. With multi-line selection (multiple client-rects): uses the last client-rect
3. With no captured data in state: returns nil (no-op)

**Test case for `dismiss-popover`:**
1. Returns `[:effects/save popover-path nil]`

**Implementation:**
- `compute-popover-position`: reads `[:excerpt :captured]` from state, extracts last client-rect + panel-rect + scroll-top, applies the formula
- `dismiss-popover`: returns save-nil effect

**Update `capture` action:** Return both effects — save captured data AND dispatch `compute-popover-position`. When selection data is nil, dispatch `dismiss-popover` instead.

**Verify:** `npm run test` — new tests pass.

### Step 5: Register new actions

**File:** `src/gremllm/renderer/actions.cljs` (line 207, after existing excerpt registration)

**Add:**
```clojure
(nxr/register-action! :excerpt.actions/compute-popover-position excerpt/compute-popover-position)
(nxr/register-action! :excerpt.actions/dismiss-popover excerpt/dismiss-popover)
```

### Step 6: Render the popover in `ui.cljs`

**File:** `src/gremllm/renderer/ui.cljs`

**Changes:**
- Require `renderer.state.excerpt` 
- In `render-workspace`, read `(excerpt-state/get-popover state)`
- Inside `[e/document-panel ...]`, after `render-document`, conditionally render:
```clojure
(when popover-pos
  [:div {:style {:position   "absolute"
                 :top        (str (:top popover-pos) "px")
                 :left       (str (:left popover-pos) "px")
                 :z-index    5
                 :background "var(--pico-primary)"
                 :color      "var(--pico-primary-inverse)"
                 :padding    "4px 8px"
                 :border-radius "4px"
                 :font-size  "0.85rem"}}
   "Stage"])
```

**Verify:** `npm run dev` — select text in document, colored box appears below selection. Check Dataspex for `[:excerpt :popover]` coords.

### Step 7: Add scroll-dismiss listener

**File:** `src/gremllm/renderer/ui.cljs`

The document panel needs a scroll listener. Since `<section.document-panel>` is rendered in `ui.cljs` via `[e/document-panel ...]`, add the scroll handler there:

```clojure
[e/document-panel {:on {:scroll [[:excerpt.actions/dismiss-popover]]}}
  ...]
```

**Verify:** `npm run dev` — select text, see popover, scroll the document panel, popover disappears. Confirm `[:excerpt :popover]` is nil in Dataspex after scroll.

## Verification Checklist

1. `npm run test` — all existing + new tests pass
2. Single-line selection: colored box appears directly below, aligned with selection end
3. Multi-line selection: box appears below the last line
4. Scroll: box dismisses when document panel scrolls
5. Dataspex: `[:excerpt :captured]` shows `panel-rect`, `panel-scroll-top`, `mouse-x`, `mouse-y`; `[:excerpt :popover]` shows `{:top :left}` after selection, `nil` after dismiss
6. Window resize: box remains correctly positioned or dismisses (both acceptable for spike)
