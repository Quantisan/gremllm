# Excerpt-Anchored Sessions — Slice 1: Rendering + Navigation

**Date:** 2026-05-27
**Status:** Draft
**Parent spec:** `docs/specs/2026-05-25-excerpt-anchored-sessions-design.md`

## Goal

Prove that margin bars work as a navigation surface against real document content. Slice 1 builds the visual layer — gutter, bars, anchor highlights, popover-driven creation — and removes the old nav overlay. Sessions are shell-only (no ACP connection) and do not survive restart. The app is intentionally broken between slices: you can create and switch sessions, but not chat.

**What Slice 1 learns:**
- Do bars position correctly against real document content?
- Does the visual treatment (colors, opacity, gutter width) work?
- Does popover-driven creation feel right?
- How does click-to-switch from the margin feel?

## Anchor Schema

The `Topic` map gains one field:

```
:anchor  ;; DocumentExcerpt — the text selection that spawned this session
```

Reuses the existing `DocumentExcerpt` shape:

```
{:id "excerpt-<uuid>"
 :text "selected text..."
 :locator {:document-relative-path "document.md"
           :start-block {:kind :paragraph :index 3 :start-line 8 :end-line 10 :block-text-snippet "..."}
           :end-block   {:kind :paragraph :index 3 :start-line 8 :end-line 10 :block-text-snippet "..."}}}
```

The anchor is required for session creation and immutable afterward. Set once when "Start session" is clicked.

**In-memory Topic shape (Slice 1):**

```
{:id "topic-<timestamp>-<random>"
 :name "New Topic"
 :anchor {:id "..." :text "..." :locator {...}}   ;; NEW — required by creation logic
 :session {}                                       ;; empty — no ACP
 :messages []                                      ;; always empty — shell sessions
 :excerpts []}                                     ;; "Add excerpt" still appends here
```

**Schema change:** Add `:anchor` as optional in the Malli `Topic` schema (required by creation logic, optional in the schema so existing codepaths don't break). `PersistedTopic` and disk codecs are not modified — the anchor exists only in memory. On restart, all sessions are lost.

**Creation timestamp:** The topic ID embeds `Date.now` (e.g., `topic-1748350000000-abc`), which serves as the creation timestamp for ordering. No separate `:created-at` field in Slice 1. Formalize in Slice 2 with persistence.

## Gutter Layout

A dedicated 24px right gutter column between the document content and the chat panel border.

**Structure:**

```
┌─────────────────────────────────┬──────┬─────────────────────┐
│         Document content        │Gutter│     Chat panel       │
│         (article)               │ 24px │                      │
│         flex: 3                 │      │     flex: 2          │
└─────────────────────────────────┴──────┴─────────────────────┘
```

- The gutter is a sibling of `<article>` inside `.document-panel`, not a separate flex item in the app layout.
- Both article and gutter live inside the scrolling `.document-panel` (`overflow-y: auto`), so bars scroll naturally with the document. No scroll listeners or translateY math.
- Gutter background matches the document panel (parchment). A subtle left border separates it from content.
- The gutter's 24px comes from within the document panel's flex allocation.

**Layout change from current:** The three-column layout (nav-strip 48px | document flex:3 | chat flex:2) becomes two-column (document+gutter flex:3 | chat flex:2). The document panel reclaims the nav-strip's 48px.

## Margin Bar Rendering

### Bar Positioning

Each bar's vertical position and height are computed from the DOM rects of the anchor's block range:

1. The anchor's `locator.start-block` and `locator.end-block` identify which rendered blocks in `<article>` the anchor spans.
2. The rendering code walks the article's child elements, finds the blocks matching the locator's indices, and measures their bounding rects relative to the scroll container.
3. The bar's `top` = top edge of start-block rect. The bar's `height` = bottom edge of end-block rect minus top edge of start-block rect.

This reuses the same block-matching logic the excerpt highlight system already employs.

### Bar Visual

- **Width:** 5px
- **Border-radius:** 3px
- **Positioned:** `position: absolute` within the gutter, `right: 6px`

### Bar States

| State | Opacity | Additional |
|-------|---------|------------|
| Active | 1.0 | Subtle box-shadow glow in session color |
| Inactive | 0.35 | — |
| Hover (inactive) | 0.70 | Anchor text gets preview highlight at ~8% opacity |

### Click Target

The full 24px gutter width at a bar's vertical span is clickable, not just the 5px bar. A faint background wash on hover (session color at ~8%) reveals the clickable area. Implemented as a transparent overlay `<button>` spanning the gutter width at bar height.

## Session Color Palette

New CSS custom properties, separate from the TVA semantic tokens:

```css
:root {
  --session-color-1: #E07634;  /* warm orange */
  --session-color-2: #3D8B8A;  /* teal */
  --session-color-3: #7B5EA7;  /* muted purple */
  --session-color-4: #C4534A;  /* dusty red */
  --session-color-5: #4A7B3F;  /* forest green */
}
```

Colors assigned by creation order, rotating modulo 5. The color index is derived from the session's position in the list of all topics sorted by ID ascending (IDs embed creation timestamps, so this is chronological order). No color field is stored on the topic.

## Popover Changes

The text-selection popover changes from a single button to a two-action popover.

### Layout

Two buttons: **"Start session"** and **"Add excerpt."** Both always visible. "Add excerpt" is visually disabled (reduced opacity, no pointer events) when no active session exists (`active-topic-id` is nil).

### Positioning and Dismissal

Unchanged. Same `popover-position` derivation from selection client rects. Scroll and mousedown on the document panel dismiss it.

### "Start session" Flow

1. User selects text in the document.
2. Popover appears at the selection.
3. User clicks "Start session."
4. Action creates a new Topic with `:anchor` set to a `DocumentExcerpt` built from the current selection (reuses `capture->excerpt` construction from existing excerpt code).
5. The new topic becomes active — its margin bar appears in the gutter, its anchor text gets highlighted.
6. Chat panel switches to this session (shell mode — no messages, shows empty state).
7. Popover dismisses.

### "Add excerpt" Flow

Identical to today — appends a `DocumentExcerpt` to the active topic's `:excerpts` vector. The only change is that it's disabled when no session is active.

## Anchor Highlight

The active session's anchor text receives a subtle highlight in the document using the CSS Custom Highlight API.

### Highlight Registries

| Registry | Purpose | Color |
|----------|---------|-------|
| `excerpt` | Existing — conversation excerpts | `--tva-amber` at 18% (unchanged) |
| `anchor` | New — active session's anchor text | Active session's `--session-color-*` at ~12% |
| `anchor-preview` | New — hovered inactive bar's anchor | Hovered session's `--session-color-*` at ~8% |

### Behavior

- Only the active session's anchor is highlighted via the `anchor` registry. Inactive anchors show nothing in the document.
- On session switch (bar click), the `anchor` highlight updates: clears old, highlights new.
- On bar hover, the `anchor-preview` registry shows a transient highlight. Clears on mouse-leave.
- Both use the same text-matching strategy as the existing excerpt highlight (`String.indexOf` with newline normalization).
- If anchor and excerpt highlights overlap, both apply (browser composites them).

### Implementation

The `sync!` function in `highlights.cljs` gains additional passes for the `anchor` and `anchor-preview` registries. The anchor text source is `(get-in state [:topics active-topic-id :anchor :text])`.

**Per-session color in highlights:** The CSS Custom Highlight API applies a single style per registry via `::highlight(name)`. Since the anchor color must match the active session's color (which changes on switch), the `::highlight(anchor)` rule uses a CSS variable (e.g., `--active-session-color`) that is updated on the gutter container or document panel when the active session changes. Same approach for `::highlight(anchor-preview)` with `--preview-session-color`.

## Navigation & Session Switching

### Bar-Click Switching

Clicking anywhere in a bar's click target dispatches `[:topic.actions/set-active topic-id]` — the same action used by the old topic list. The clicked session's bar goes to full opacity; the previously active bar dims. The chat panel switches to the new session. The anchor highlight moves.

### Auto-Activation on Document Open

When a document opens with existing in-memory sessions, the most recently created session is auto-activated. "Most recently created" = sorted by topic ID descending (the ID embeds `Date.now`). No new timestamp field needed.

When a document opens with no sessions, no session is active.

### Empty State

- **Gutter:** Present but empty (no bars).
- **Active session:** None (`active-topic-id` is nil).
- **Chat panel:** Shows a prompt — "Select text in the document to start a session." The composer/input footer is hidden when no session is active.

### Shell Session Chat State

When a session is active but is a shell (no ACP — all Slice 1 sessions), the chat panel shows the session's anchor text as context and a message indicating the session is not yet connected. The composer is visible but disabled.

## Nav Overlay Removal

Clean break. The following are removed:

- **Nav-strip** — the 48px left column with 📁 toggle
- **Nav overlay** — `<aside.nav-overlay>` and its contents
- **Topic list** — `render-left-panel-content`, `render-topic-item` in `topics.cljs`
- **"New Topic" link**
- **Topic rename UI** — inline input, rename actions, `:topics-ui` state
- **`[:ui :nav-expanded?]` state** and `toggle-nav` action

The following are kept:

- `state/topic.cljs` — state paths still used
- `actions/topic.cljs` — `set-active`, `mark-unsaved`, `auto-save`, `finalize-turn` remain
- Topic persistence to disk — the codec still writes/reads `PersistedTopic`, but Slice 1 sessions are shell-only (empty messages, no ACP), so auto-save has nothing meaningful to persist. On restart, loaded topics lack `:anchor` and won't render margin bars — effectively invisible. Slice 2 wires anchor persistence.

## Accessibility

Minimum viable surface for Slice 1 — bars are usable without a mouse.

### Included

- Each margin bar click target is a `<button>` element with `aria-label` describing the session: `"Session: <truncated anchor text>"`.
- Active bar: `aria-pressed="true"`. Inactive bars: `aria-pressed="false"`.
- Bars are focusable and activatable via Enter/Space (semantic `<button>` behavior).
- Gutter container: `role="toolbar"` and `aria-label="Document sessions"`.

### Deferred

- Keyboard shortcut to cycle sessions (e.g., Ctrl+Shift+Up/Down)
- Screen reader announcement on session switch
- Accessible popover trigger from keyboard (pre-existing gap — the popover requires mouse text selection today)

## Session Deletion

Not included, per the parent spec. Sessions accumulate. They do not survive restart in Slice 1, so accumulation is bounded by the current app session.

## Out of Scope (Slice 1)

- ACP session connection — sessions are shell-only
- Anchor persistence — sessions don't survive restart
- Chat functionality — composer is disabled
- Topic → Session rename — happens naturally during Slice 2 or after
- Session naming — bars and anchor highlights identify sessions
- Anchor versioning mechanics — design decision made in parent spec, implementation deferred
- Density management for many sessions
- Session deletion UI
