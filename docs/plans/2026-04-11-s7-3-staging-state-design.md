# S7.3: Staging State & Staging Zone — Design Spec

**Date:** 2026-04-11
**Slice:** S7.3 from `2026-02-11-scooter-vertical-slices.md`
**Depends on:** S7.1 (shipped), S7.2 (shipped)
**Prior art:** `2026-04-10-topic-staged-selections-design.md` (state path decision)

## Problem

Users select text in the document panel and a popover appears (S7.1/S7.2). There is nowhere for those selections to accumulate before being sent as AI context. S7.3 introduces the staging state on the topic and a visible zone above the chat input that displays staged selections as dismissible pills.

## Scope

S7.3 delivers staging **state, actions, UI rendering, and persistence**. It does not wire the popover "Stage" button (S7.4) or highlight staged regions in the document (S7.5). The testable result is: inject staged selections via Dataspex, see pills render, dismiss one, clear all.

## Schema

**File:** `src/gremllm/schema.cljs`

Add `StagedSelection` and extend `PersistedTopic`:

```clojure
(def StagedSelection
  [:map
   [:id :string]
   [:selection CapturedSelection]])
```

`PersistedTopic` gains:

```clojure
[:staged-selections {:default []} [:vector StagedSelection]]
```

ID generation uses `(str "staged-" (random-uuid))` inside the `stage` action, matching the `generate-topic-id` pattern.

## State

**File:** `src/gremllm/renderer/state/topic.cljs`

Add `get-staged-selections [state]` — reads `[:topics <active-topic-id> :staged-selections]`, returns the vector or `[]`.

## Actions

**File:** `src/gremllm/renderer/actions/topic.cljs`
**Registration:** `src/gremllm/renderer/actions.cljs`

Three pure actions, registered under the `staging.actions` namespace prefix to stay distinct from topic CRUD while colocating in the same file:

| Action | Signature | Behavior |
|--------|-----------|----------|
| `stage` | `[state selection]` | Wraps raw `CapturedSelection` as `{:id (generate) :selection selection}`, appends to active topic's `:staged-selections` |
| `unstage` | `[state id]` | Removes item with matching `:id` from the vector |
| `clear-staged` | `[state]` | Resets vector to `[]` |

Each returns `[[:effects/save <path> <updated-vec>]]`.

Registration:

```clojure
(nxr/register-action! :staging.actions/stage topic/stage)
(nxr/register-action! :staging.actions/unstage topic/unstage)
(nxr/register-action! :staging.actions/clear-staged topic/clear-staged)
```

## UI

**File:** `src/gremllm/renderer/ui/chat.cljs`

Add `render-staged-selections` — rendered inside the form above the fieldset, in the same position as `render-attachment-indicator`. Separate function, same structural pattern.

Structure:

```clojure
(when (seq staged-selections)
  [:div.staged-selections
    (for [{:keys [id selection]} staged-selections]
      [:span.staged-pill {:key id}
        "selection: " (truncate (:text selection) 30) "…"
        [:button.dismiss
         {:on {:click [[:staging.actions/unstage id]]}}
         "✕"]])
    (when (> (count staged-selections) 1)
      [:button {:on {:click [[:staging.actions/clear-staged]]}}
       "Clear all"])])
```

Each pill shows `selection: The executive su…` with `✕` to dismiss. "Clear all" appears only with 2+ selections. `truncate` is a private helper in `chat.cljs` — clips to `n` characters and appends `…`.

### Prop threading

`render-input-form` receives `:staged-selections` from `render-workspace`, which reads via `topic-state/get-staged-selections`.

## Persistence

No codec changes needed. Adding `:staged-selections` to `PersistedTopic` means:
- **Save:** `topic-for-disk` (strip-extra-keys against `PersistedTopic`) includes it automatically.
- **Load:** `topic-from-disk` (default-value-transformer) fills `[]` for old topics missing the field.

## CSS

Minimal styling in `index.html`:
- `.staged-selections` — flex row, wrap, gap between pills
- `.staged-pill` — inline-flex, small font, muted background, border-radius for pill shape, truncated text
- `.dismiss` — small inline button, no border

Use `var(--pico-*)` tokens. No hex literals.

## Tests

**File:** `test/gremllm/renderer/actions/topic_test.cljs` (extend existing)

Test the three actions using fixtures from `schema_test.cljs`:

- **`stage`**: appends a wrapped selection; ID is a string starting with `"staged-"`; selection data preserved
- **`unstage`**: removes by ID; no-op when ID not found; vector shrinks by one
- **`clear-staged`**: resets to `[]` regardless of content

## Files modified

| File | Change |
|------|--------|
| `src/gremllm/schema.cljs` | Add `StagedSelection`, extend `PersistedTopic` |
| `src/gremllm/renderer/state/topic.cljs` | Add `get-staged-selections` |
| `src/gremllm/renderer/actions/topic.cljs` | Add `stage`, `unstage`, `clear-staged` |
| `src/gremllm/renderer/actions.cljs` | Register three `staging.actions/*` actions |
| `src/gremllm/renderer/ui/chat.cljs` | Add `render-staged-selections`, thread into form |
| `src/gremllm/renderer/ui.cljs` | Read staged selections from state, pass to input form |
| `resources/public/index.html` | Pill and zone CSS |
| `test/gremllm/renderer/actions/topic_test.cljs` | Action unit tests |

## Verification

1. `npm run test` — all existing + new action tests pass
2. Open the app, open a workspace with a topic
3. In Dataspex, inject a value at `[:topics <topic-id> :staged-selections]` with one or more `{:id "staged-test" :selection <fixture>}` entries
4. Pills appear above the chat input
5. Click `✕` on a pill — it disappears
6. Inject 2+ selections — "Clear all" appears; click it — all pills disappear
7. Switch topics and back — staged selections persist
8. Restart the app — staged selections survive (persisted to disk)
