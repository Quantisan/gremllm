# Excerpt-Anchored Sessions — Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the visual layer for margin bars, anchor highlights, popover-driven session creation, and bar-click navigation — proving that margin bars work as a navigation surface against real document content. Sessions are shell-only (no ACP connection) and don't survive restart.

**Architecture:** The gutter is a 24px column rendered as a sibling of `<article>` inside the scrolling `.document-panel`. Bars are absolutely positioned `<button>` elements whose `top`/`height` are computed from the anchor's block range in the DOM, re-synced on every Replicant render via the existing `on-render-sync` lifecycle hook. The old nav overlay (48px strip + aside + topic list) is removed entirely. Session switching dispatches `[:topic.actions/set-active]` without triggering ACP init (Slice 1 decoupling).

**Tech Stack:** ClojureScript, Nexus (state management), Replicant (UI/hiccup), CSS Custom Highlight API, PicoCSS, Malli (schema validation)

**Design decisions:**
- **ACP decoupling:** `set-active` in Slice 1 no longer dispatches `:acp.effects/init-session`. All sessions are shell-only. ACP init is re-wired in Slice 2.
- **Auto-activation filter:** When a document opens with existing topics, only topics with `:anchor` are candidates for auto-activation (disk-restored pre-Slice-1 topics lack anchors and are invisible).
- **`initialize-empty` change:** Opening a document with no topics no longer auto-creates a topic. The document opens with no active session; the user creates one via text selection.
- **Bar positioning via lifecycle hook:** `gutter/sync!` runs inside the existing `on-render-sync` callback after `highlights/sync!`, measuring block DOM rects via `data-grem-block-*` attrs already stamped by `locator/sync-block-metadata!`.
- **Anchor highlight coloring via clear-and-reset:** The `::highlight(anchor)` pseudo uses CSS variables for background color. To avoid relying on browser repaint behavior for variable changes, `sync-anchor!` and `sync-anchor-preview!` always clear and re-set the registry entry on each call — the same strategy `highlights/sync!` already uses for excerpts. This is the safe approach; the CSS variables on `.document-panel` still provide the color values via `::highlight()` rules.

---

## File Map

### New Files
| File | Responsibility |
|------|----------------|
| `src/gremllm/renderer/ui/document/gutter.cljs` | Gutter container rendering + `sync!` (bar position measurement) |
| `test/gremllm/renderer/ui/document/gutter_test.cljs` | Bar rendering and sync tests |
| `src/gremllm/renderer/state/session.cljs` | Session color + hovered-bar state paths |
| `test/gremllm/renderer/state/session_test.cljs` | Session color derivation tests |

### Modified Files
| File | Changes |
|------|---------|
| `src/gremllm/schema.cljs` | Add optional `:anchor` field to `Topic` |
| `src/gremllm/renderer/ui.cljs` | Remove nav strip/overlay, add gutter, restructure popover to two-button, add chat empty states |
| `src/gremllm/renderer/ui/elements.cljs` | Remove `nav-strip`, `nav-overlay`, `topic-item`; add `session-gutter` |
| `src/gremllm/renderer/ui/document.cljs` | Pass session state through `on-render-sync`, invoke `gutter/sync!` |
| `src/gremllm/renderer/ui/document/highlights.cljs` | Add `anchor` and `anchor-preview` registries alongside `excerpt` |
| `src/gremllm/renderer/ui/chat.cljs` | Add empty-state and shell-session renders, disable composer when no active session |
| `src/gremllm/renderer/state/ui.cljs` | Remove `nav-expanded-path`; add `hovered-bar-topic-id-path` |
| `src/gremllm/renderer/actions/topic.cljs` | Add `start-from-selection`, decouple `set-active` from ACP init |
| `src/gremllm/renderer/actions/excerpt.cljs` | `add` works only when `active-topic-id` exists (already gated) |
| `src/gremllm/renderer/actions/document.cljs` | Change `initialize-empty` to not create a topic; change `restore-with-topics` auto-activation to filter anchored topics |
| `src/gremllm/renderer/actions/ui.cljs` | Remove `toggle-nav` |
| `src/gremllm/renderer/actions.cljs` | Register new actions, remove old nav/rename registrations |
| `resources/public/index.html` | Add session color palette, gutter CSS, anchor/preview highlight rules, remove nav strip CSS |

### Deleted Files
| File | Reason |
|------|--------|
| `src/gremllm/renderer/ui/topics.cljs` | Nav overlay UI removed |
| `test/gremllm/renderer/ui/topics_test.cljs` | Tests for deleted UI |

---

## Task 1: Schema — Add `:anchor` to Topic

**Files:**
- Modify: `src/gremllm/schema.cljs:196-202`
- Test: `test/gremllm/schema_test.cljs`

- [ ] **Step 1: Write the failing test**

In `test/gremllm/schema_test.cljs`, add:

```clojure
(deftest topic-with-anchor-test
  (let [block {:kind :paragraph
               :index 2
               :start-line 3
               :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}
        anchor {:id "excerpt-abc"
                :text "launched on a Tuesday"
                :locator {:document-relative-path "document.md"
                          :start-block block
                          :end-block block}}]
    (testing "Topic with anchor validates"
      (is (m/validate schema/Topic
                      {:id "topic-123-abc"
                       :name "New Topic"
                       :anchor anchor
                       :session {}
                       :messages []
                       :excerpts []})))
    (testing "Topic without anchor still validates (optional field)"
      (is (m/validate schema/Topic
                      {:id "topic-123-abc"
                       :name "New Topic"
                       :session {}
                       :messages []
                       :excerpts []})))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — `:anchor` is not recognized by the closed schema.

- [ ] **Step 3: Add optional `:anchor` field to Topic**

In `src/gremllm/schema.cljs`, modify the `Topic` definition:

```clojure
(def Topic
  "Schema for topics in app state (includes transient fields)"
  (mu/merge
    PersistedTopic
    [:map
     [:anchor {:optional true} DocumentExcerpt]
     [:unsaved? {:optional true} :boolean]]))
```

Note: `:anchor` is added to `Topic` only, not `PersistedTopic`. It exists only in memory for Slice 1.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/schema.cljs test/gremllm/schema_test.cljs
git commit -m "feat(schema): add optional :anchor field to Topic for session anchoring"
```

---

## Task 2: Session State — Color Derivation and Hover State

**Files:**
- Create: `src/gremllm/renderer/state/session.cljs`
- Create: `test/gremllm/renderer/state/session_test.cljs`

- [ ] **Step 1: Write the failing tests**

Create `test/gremllm/renderer/state/session_test.cljs`:

```clojure
(ns gremllm.renderer.state.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.state.session :as session-state]))

(def session-colors
  ["#E07634" "#3D8B8A" "#7B5EA7" "#C4534A" "#4A7B3F"])

(deftest color-for-topic-test
  (let [topics-map {"topic-1000-a" {:id "topic-1000-a" :name "T1"}
                    "topic-2000-b" {:id "topic-2000-b" :name "T2"}
                    "topic-3000-c" {:id "topic-3000-c" :name "T3"}}]
    (testing "first topic gets color 1"
      (is (= "#E07634" (session-state/color-for-topic topics-map "topic-1000-a"))))
    (testing "second topic gets color 2"
      (is (= "#3D8B8A" (session-state/color-for-topic topics-map "topic-2000-b"))))
    (testing "third topic gets color 3"
      (is (= "#7B5EA7" (session-state/color-for-topic topics-map "topic-3000-c"))))))

(deftest color-wraps-modulo-5-test
  (let [topics-map (into {} (map-indexed
                              (fn [i _] [(str "topic-" (* (inc i) 1000) "-x")
                                         {:id (str "topic-" (* (inc i) 1000) "-x")}])
                              (range 7)))]
    (testing "6th topic wraps to color 1"
      (let [sorted-ids (sort (keys topics-map))]
        (is (= (session-state/color-for-topic topics-map (nth sorted-ids 5))
               (session-state/color-for-topic topics-map (nth sorted-ids 0))))))))

(deftest anchored-topics-sorted-test
  (let [topics-map {"topic-3000-c" {:id "topic-3000-c" :anchor {:id "e1" :text "x" :locator {}}}
                    "topic-1000-a" {:id "topic-1000-a"}
                    "topic-2000-b" {:id "topic-2000-b" :anchor {:id "e2" :text "y" :locator {}}}}]
    (testing "returns only anchored topics sorted by id ascending"
      (is (= ["topic-2000-b" "topic-3000-c"]
             (mapv :id (session-state/anchored-topics-sorted topics-map)))))
    (testing "most-recent-anchored returns last by id descending"
      (is (= "topic-3000-c"
             (:id (session-state/most-recent-anchored topics-map)))))))

(deftest no-anchored-topics-test
  (let [topics-map {"topic-1000-a" {:id "topic-1000-a"}}]
    (testing "returns empty when no anchored topics"
      (is (empty? (session-state/anchored-topics-sorted topics-map))))
    (testing "most-recent-anchored returns nil"
      (is (nil? (session-state/most-recent-anchored topics-map))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — namespace doesn't exist.

- [ ] **Step 3: Implement session state**

Create `src/gremllm/renderer/state/session.cljs`:

```clojure
(ns gremllm.renderer.state.session)

(def session-colors
  ["#E07634" "#3D8B8A" "#7B5EA7" "#C4534A" "#4A7B3F"])

(def hovered-bar-topic-id-path [:ui :hovered-bar-topic-id])

(defn get-hovered-bar-topic-id [state]
  (get-in state hovered-bar-topic-id-path))

(defn anchored-topics-sorted
  "Returns anchored topics sorted by id ascending (chronological by creation)."
  [topics-map]
  (->> (vals topics-map)
       (filter :anchor)
       (sort-by :id)))

(defn most-recent-anchored
  "Returns the most recently created anchored topic, or nil."
  [topics-map]
  (last (anchored-topics-sorted topics-map)))

(defn color-for-topic
  "Derives the session color for a topic from its position in the
   sorted topic list. Colors rotate modulo 5."
  [topics-map topic-id]
  (let [sorted-ids (sort (keys topics-map))
        idx (.indexOf sorted-ids topic-id)]
    (when-not (neg? idx)
      (nth session-colors (mod idx (count session-colors))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/state/session.cljs test/gremllm/renderer/state/session_test.cljs
git commit -m "feat(state): session color derivation, anchored-topic helpers, and hover state path"
```

---

## Task 3: Decouple `set-active` from ACP Init

**Files:**
- Modify: `src/gremllm/renderer/actions/topic.cljs:134-138`
- Test: `test/gremllm/renderer/actions/topic_test.cljs`

- [ ] **Step 1: Write the failing test**

In `test/gremllm/renderer/actions/topic_test.cljs`, add:

```clojure
(deftest set-active-does-not-init-acp-test
  (testing "set-active only saves active-topic-id, no ACP init"
    (let [result (topic/set-active {} "topic-123")]
      (is (= [[:effects/save topic-state/active-topic-id-path "topic-123"]]
             result)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — current `set-active` returns two effects including `:acp.effects/init-session`.

- [ ] **Step 3: Remove ACP init from set-active**

In `src/gremllm/renderer/actions/topic.cljs`, change `set-active`:

```clojure
(defn set-active
  "Set the active topic. ACP session init is handled separately."
  [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path topic-id]])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS (check no other tests relied on the old `set-active` dispatching ACP init)

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/actions/topic.cljs test/gremllm/renderer/actions/topic_test.cljs
git commit -m "refactor(topic): decouple set-active from ACP init for shell sessions"
```

---

## Task 4: Topic Creation from Selection — `start-from-selection` and `start-session-from-capture`

**Files:**
- Modify: `src/gremllm/renderer/actions/topic.cljs`
- Modify: `src/gremllm/renderer/actions.cljs`
- Test: `test/gremllm/renderer/actions/topic_test.cljs`

- [ ] **Step 1: Write the failing tests**

In `test/gremllm/renderer/actions/topic_test.cljs`, add:

```clojure
(deftest start-from-selection-test
  (let [anchor {:id "excerpt-abc"
                :text "launched on a Tuesday"
                :locator {:document-relative-path "document.md"
                          :start-block {:kind :paragraph :index 2
                                        :start-line 3 :end-line 3
                                        :block-text-snippet "Our Gremllm"}
                          :end-block {:kind :paragraph :index 2
                                      :start-line 3 :end-line 3
                                      :block-text-snippet "Our Gremllm"}}}
        result (topic/start-from-selection {} anchor)
        [[_ topic-path saved-topic] [set-active-action set-active-id] dismiss-action] result]

    (is (= 3 (count result)) "should return save, set-active, dismiss-popover")

    (is (= :effects/save (first (first result))))
    (is (= anchor (:anchor saved-topic)) "anchor is set on the new topic")
    (is (= "New Topic" (:name saved-topic)))
    (is (= [] (:messages saved-topic)))
    (is (= [] (:excerpts saved-topic)))
    (is (string? (:id saved-topic)))
    (is (= (topic-state/topic-path (:id saved-topic)) topic-path))

    (is (= :topic.actions/set-active set-active-action))
    (is (= (:id saved-topic) set-active-id))

    (is (= [:excerpt.actions/dismiss-popover] dismiss-action))))

(deftest start-session-from-capture-test
  (let [block {:kind :paragraph :index 2
               :start-line 3 :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}
        state {:excerpt {:captured {:text "launched on a Tuesday"
                                    :range-count 1
                                    :anchor-node "#text"
                                    :anchor-offset 0
                                    :focus-node "#text"
                                    :focus-offset 21
                                    :range {:bounding-rect {:height 17 :left 100 :top 50 :width 200}
                                            :client-rects [{:height 17 :left 100 :top 50 :width 200}]
                                            :common-ancestor "#text"
                                            :start-container "#text"
                                            :start-text "Our Gremllm launched on a Tuesday."
                                            :start-offset 15
                                            :end-container "#text"
                                            :end-text "Our Gremllm launched on a Tuesday."
                                            :end-offset 36}}
                         :locator-hints {:document-relative-path "document.md"
                                         :start-block block
                                         :end-block block}}}
        result (topic/start-session-from-capture state)]
    (testing "returns effects when captured state exists"
      (is (= 3 (count result)))
      (is (= :effects/save (first (first result))))
      (let [[_ _ saved-topic] (first result)]
        (is (= "launched on a Tuesday" (get-in saved-topic [:anchor :text])))))
    (testing "returns nil when no captured state"
      (is (nil? (topic/start-session-from-capture {}))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — `start-from-selection` and `start-session-from-capture` don't exist.

- [ ] **Step 3: Implement both actions**

In `src/gremllm/renderer/actions/topic.cljs`, add requires and both functions:

Add to the ns requires:

```clojure
[gremllm.renderer.state.excerpt :as excerpt-state]
[gremllm.renderer.actions.excerpt :as excerpt]
```

Add the functions:

```clojure
(defn start-from-selection
  "Create a new shell session anchored to the given excerpt."
  [_state anchor]
  (let [new-topic (assoc (schema/create-topic) :anchor anchor)
        topic-id  (:id new-topic)]
    [[:effects/save (topic-state/topic-path topic-id) new-topic]
     [:topic.actions/set-active topic-id]
     [:excerpt.actions/dismiss-popover]]))

(defn start-session-from-capture
  "Build an anchor from the current excerpt capture state, then create a session."
  [state]
  (let [captured (excerpt-state/get-captured state)
        locator-hints (excerpt-state/get-locator-hints state)]
    (when (and captured locator-hints)
      (let [anchor (excerpt/capture->excerpt captured locator-hints
                                             (str "excerpt-" (random-uuid)))]
        (start-from-selection state anchor)))))
```

- [ ] **Step 4: Register both actions**

In `src/gremllm/renderer/actions.cljs`, add among the Topic registrations:

```clojure
(nxr/register-action! :topic.actions/start-from-selection topic/start-from-selection)
(nxr/register-action! :topic.actions/start-session-from-capture topic/start-session-from-capture)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/actions/topic.cljs src/gremllm/renderer/actions.cljs test/gremllm/renderer/actions/topic_test.cljs
git commit -m "feat(topic): start-from-selection and start-session-from-capture actions"
```

---

## Task 5: Document Open — Auto-activate Anchored Sessions

**Files:**
- Modify: `src/gremllm/renderer/actions/document.cljs`
- Test: `test/gremllm/renderer/actions/document_test.cljs`

- [ ] **Step 1: Write/update the failing tests**

In `test/gremllm/renderer/actions/document_test.cljs`, update existing tests and add new ones:

```clojure
;; Add to requires:
;; [gremllm.renderer.state.session :as session-state]

(deftest initialize-empty-no-topic-test
  (testing "Empty document does not create a topic — just marks loaded"
    (let [effects (document/initialize-empty {})]
      (is (= [[:document.actions/mark-loaded]] effects))
      (is (not (has-action? effects :topic.actions/start-new))))))

(deftest restore-auto-activates-anchored-test
  (let [anchor {:id "e1" :text "x" :locator {:document-relative-path "document.md"
                                              :start-block {:kind :paragraph :index 1 :start-line 1 :end-line 1 :block-text-snippet "x"}
                                              :end-block {:kind :paragraph :index 1 :start-line 1 :end-line 1 :block-text-snippet "x"}}}
        old-topic {:id "topic-1000-a" :name "Old" :session {} :messages [] :excerpts []}
        new-topic {:id "topic-2000-b" :name "New" :anchor anchor :session {} :messages [] :excerpts []}]
    (testing "activates most recent anchored topic, ignoring unanchored"
      (let [effects (document/restore-with-topics {}
                      {:topics {"topic-1000-a" old-topic
                                "topic-2000-b" new-topic}
                       :active-topic-id nil})
            [_ activated-id] (get-action effects :topic.actions/set-active)]
        (is (= "topic-2000-b" activated-id))))
    (testing "no activation when no anchored topics"
      (let [effects (document/restore-with-topics {}
                      {:topics {"topic-1000-a" old-topic}
                       :active-topic-id nil})]
        (is (not (has-action? effects :topic.actions/set-active)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — `initialize-empty` still creates a topic; `restore-with-topics` doesn't filter by anchor.

- [ ] **Step 3: Update `initialize-empty` and `restore-with-topics`**

In `src/gremllm/renderer/actions/document.cljs`:

```clojure
(ns gremllm.renderer.actions.document
  (:require [gremllm.schema.codec :as codec]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.session :as session-state]))

(defn restore-with-topics
  "Restore a document that has existing topics.
   Auto-activates the most recently created anchored session."
  [_state {:keys [topics]}]
  (let [recent (session-state/most-recent-anchored topics)]
    (cond-> [[:effects/save topic-state/topics-path topics]]
      recent (conj [:topic.actions/set-active (:id recent)])
      true   (conj [:document.actions/mark-loaded]))))

(defn initialize-empty
  "Initialize an empty document with no active session."
  [_state]
  [[:document.actions/mark-loaded]])
```

Also update `opened` to not pass `:active-topic-id` (it's now derived inside `restore-with-topics`):

```clojure
(defn opened
  "A document has been opened/loaded from disk."
  [_state sync-data-js]
  (let [{:keys [topics document-meta document]} (codec/document-sync-from-ipc sync-data-js)]
    (cond-> [[:document.actions/set-meta document-meta]
             [:document.actions/set-content (:content document)]]
      (empty? topics) (conj [:document.actions/initialize-empty])
      (seq topics)    (conj [:document.actions/restore-with-topics {:topics topics}]))))
```

- [ ] **Step 4: Update existing document tests**

In `test/gremllm/renderer/actions/document_test.cljs`, update the existing tests to match the new signatures:

```clojure
;; Replace the "Document with topics restores them" test in opened-test:

(testing "Document with topics restores them"
  (let [topic (schema/create-topic)
        sync-data (create-sync-data-js {:topics {"tid" topic}})
        effects (document/opened {} sync-data)
        [_ restore-params] (get-action effects :document.actions/restore-with-topics)]
    (is (has-action? effects :document.actions/set-meta))
    (is (has-action? effects :document.actions/set-content))
    (is (contains? (:topics restore-params) "tid"))
    (is (not (contains? restore-params :active-topic-id))
        "opened no longer passes active-topic-id — restore derives it")))

;; Replace the entire restore-with-topics-test:

(deftest restore-with-topics-test
  (let [block {:kind :paragraph :index 1 :start-line 1 :end-line 1 :block-text-snippet "x"}
        anchor {:id "e1" :text "x" :locator {:document-relative-path "d.md"
                                              :start-block block :end-block block}}]
    (testing "Activates most recent anchored topic"
      (let [old-topic (assoc (schema/create-topic) :id "topic-1000-a")
            new-topic (assoc (schema/create-topic) :id "topic-2000-b" :anchor anchor)
            effects (document/restore-with-topics {} {:topics {"topic-1000-a" old-topic
                                                                "topic-2000-b" new-topic}})
            [_ activated-id] (get-action effects :topic.actions/set-active)]
        (is (= "topic-2000-b" activated-id))))

    (testing "No activation when no anchored topics"
      (let [old-topic (assoc (schema/create-topic) :id "topic-1000-a")
            effects (document/restore-with-topics {} {:topics {"topic-1000-a" old-topic}})]
        (is (not (has-action? effects :topic.actions/set-active)))))

    (testing "Restores all topics to state"
      (let [topic (assoc (schema/create-topic) :id "tid" :anchor anchor)
            effects (document/restore-with-topics {} {:topics {"tid" topic}})
            [_ topics-path saved-topics] (get-action effects :effects/save)]
        (is (= [:topics] topics-path))
        (is (contains? saved-topics "tid"))))))
```

Also add the `session-state` require to the test ns:

```clojure
[gremllm.renderer.state.session :as session-state]
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/actions/document.cljs test/gremllm/renderer/actions/document_test.cljs
git commit -m "feat(document): auto-activate most recent anchored session on open"
```

---

## Task 6: Nav Overlay Removal

**Files:**
- Delete: `src/gremllm/renderer/ui/topics.cljs`
- Delete: `test/gremllm/renderer/ui/topics_test.cljs`
- Modify: `src/gremllm/renderer/ui/elements.cljs`
- Modify: `src/gremllm/renderer/ui.cljs`
- Modify: `src/gremllm/renderer/state/ui.cljs`
- Modify: `src/gremllm/renderer/actions/ui.cljs`
- Modify: `src/gremllm/renderer/actions/topic.cljs`
- Modify: `src/gremllm/renderer/actions.cljs`
- Modify: `resources/public/index.html`

- [ ] **Step 1: Delete topics UI and its test**

```bash
rm src/gremllm/renderer/ui/topics.cljs
rm test/gremllm/renderer/ui/topics_test.cljs
```

- [ ] **Step 2: Remove element aliases**

In `src/gremllm/renderer/ui/elements.cljs`, remove the `nav-strip`, `nav-overlay`, and `topic-item` aliases. The file should contain: `app-layout`, `document-panel`, `chat-panel`, `chat-area`, `user-message`, `assistant-message`, `reasoning-message`, `tool-status-message`, `tool-detail-message`, and the new `session-gutter` alias (added in Task 8).

- [ ] **Step 3: Remove nav state and toggle action**

In `src/gremllm/renderer/state/ui.cljs`, remove `nav-expanded-path` and `nav-expanded?`:

```clojure
(ns gremllm.renderer.state.ui)

(def renaming-topic-id-path [:topics-ui :renaming-id])

(defn renaming-topic-id [state]
  (get-in state renaming-topic-id-path))
```

Note: `renaming-topic-id-path` is kept here temporarily — it's still referenced by `actions/topic.cljs` rename functions until Task 13 removes both.

In `src/gremllm/renderer/actions/ui.cljs`, remove the `toggle-nav` function.

- [ ] **Step 4: Remove registrations in actions.cljs**

In `src/gremllm/renderer/actions.cljs`:
- Remove `(nxr/register-action! :ui.actions/toggle-nav ui/toggle-nav)` (line 153)
- Remove the `topics-ui` require from `ui.cljs`
- Remove the `ui-state` require if no longer needed (check if `renaming-topic-id-path` is still used)

- [ ] **Step 5: Update `ui.cljs` — remove nav strip and overlay**

In `src/gremllm/renderer/ui.cljs`, remove:
- The `topics-ui` require
- The `ui-state` require (for `nav-expanded?` — but keep if `renaming-topic-id` is still read)
- Zone 1 (nav-strip) from `render-app-layout`
- The `nav-expanded?` state extraction
- The `when nav-expanded?` nav-overlay block
- The `renaming-topic-id` state extraction (if rename UI is fully removed)

The layout becomes two zones: document-panel + chat-panel.

- [ ] **Step 6: Remove nav CSS from index.html**

In `resources/public/index.html`, remove:
- `.nav-strip { ... }` rule (lines 220-228)
- `.nav-overlay { ... }` rule (lines 230-241)
- `.nav-strip[data-theme=dark]` from the structural background rule (line 99)
- `.topic-item` rules (lines 190-217)

- [ ] **Step 7: Run tests to verify nothing is broken**

Run: `npm run test:ci`
Expected: PASS — deleted tests are gone, remaining tests don't reference removed code.

- [ ] **Step 8: Commit**

```bash
git add -u
git commit -m "refactor(ui): remove nav overlay, nav strip, and topic list UI"
```

---

## Task 7: CSS — Session Color Palette, Gutter, and Highlight Rules

**Files:**
- Modify: `resources/public/index.html`

- [ ] **Step 1: Add session color CSS custom properties**

In `resources/public/index.html`, add to `:root`:

```css
/* Session color palette */
--session-color-1: #E07634;
--session-color-2: #3D8B8A;
--session-color-3: #7B5EA7;
--session-color-4: #C4534A;
--session-color-5: #4A7B3F;

/* Active session color (updated by JS on session switch) */
--active-session-color: var(--session-color-1);
--preview-session-color: var(--session-color-1);
```

- [ ] **Step 2: Add gutter CSS**

```css
.session-gutter {
    flex: 0 0 24px;
    position: relative;
    border-left: 1px solid var(--pico-muted-border-color);
}

.session-gutter[role="toolbar"] {
    display: flex;
    flex-direction: column;
}

.session-bar-target {
    position: absolute;
    right: 0;
    width: 100%;
    background: none;
    border: none;
    padding: 0;
    margin: 0;
    cursor: pointer;
    display: flex;
    justify-content: center;
}

.session-bar-target:hover {
    background: color-mix(in srgb, var(--bar-color) 8%, transparent);
}

.session-bar {
    width: 5px;
    border-radius: 3px;
    height: 100%;
    position: absolute;
    right: 6px;
    top: 0;
}

.session-bar--active {
    opacity: 1;
    box-shadow: 0 0 4px color-mix(in srgb, var(--bar-color) 50%, transparent);
}

.session-bar--inactive {
    opacity: 0.35;
}

.session-bar-target:hover .session-bar--inactive {
    opacity: 0.70;
}
```

- [ ] **Step 3: Add anchor highlight CSS rules**

```css
::highlight(anchor) {
    background-color: color-mix(in srgb, var(--active-session-color) 12%, transparent);
}

::highlight(anchor-preview) {
    background-color: color-mix(in srgb, var(--preview-session-color) 8%, transparent);
}
```

- [ ] **Step 4: Update document-panel CSS for gutter sibling**

Modify `.document-panel` to use flex layout for article + gutter:

```css
.document-panel {
    flex: 3;
    min-width: 50%;
    position: relative;
    overflow-y: auto;
    padding: var(--pico-spacing);
    transition: flex 0.3s ease;
    display: flex;
    flex-direction: row;
}

.document-panel > article {
    flex: 1;
    min-width: 0;
}
```

- [ ] **Step 5: Commit**

```bash
git add resources/public/index.html
git commit -m "feat(css): session color palette, gutter layout, anchor highlight rules"
```

---

## Task 8: Gutter Component — Rendering and Sync

**Files:**
- Create: `src/gremllm/renderer/ui/document/gutter.cljs`
- Create: `test/gremllm/renderer/ui/document/gutter_test.cljs`
- Modify: `src/gremllm/renderer/ui/elements.cljs`

- [ ] **Step 1: Add `session-gutter` element alias**

In `src/gremllm/renderer/ui/elements.cljs`, add:

```clojure
(defalias session-gutter [attrs & body]
  (into [:div.session-gutter (merge {:role "toolbar"
                                     :aria-label "Document sessions"} attrs)] body))
```

- [ ] **Step 2: Write gutter rendering tests**

Create `test/gremllm/renderer/ui/document/gutter_test.cljs`:

```clojure
(ns gremllm.renderer.ui.document.gutter-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.ui.document.gutter :as gutter]
            [lookup.core :as lookup]))

(def sample-anchor
  {:id "excerpt-1"
   :text "sample text"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "sample text"}
             :end-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                         :block-text-snippet "sample text"}}})

(def sample-topics
  {"topic-1000-a" {:id "topic-1000-a" :name "First" :anchor sample-anchor}
   "topic-2000-b" {:id "topic-2000-b" :name "Second" :anchor sample-anchor}})

(deftest render-gutter-bars-test
  (let [hiccup (gutter/render-gutter sample-topics "topic-1000-a")
        buttons (lookup/select '[div button] hiccup)]
    (testing "renders a button for each anchored topic"
      (is (= 2 (count buttons))))
    (testing "active bar has aria-pressed true"
      (let [active-btn (first buttons)
            attrs (lookup/attrs active-btn)]
        (is (= "true" (:aria-pressed attrs)))))
    (testing "inactive bar has aria-pressed false"
      (let [inactive-btn (second buttons)
            attrs (lookup/attrs inactive-btn)]
        (is (= "false" (:aria-pressed attrs)))))))

(deftest render-empty-gutter-test
  (testing "renders empty gutter when no anchored topics"
    (let [hiccup (gutter/render-gutter {} nil)
          buttons (lookup/select '[div button] hiccup)]
      (is (empty? buttons)))))
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — namespace doesn't exist.

- [ ] **Step 4: Implement gutter rendering**

Create `src/gremllm/renderer/ui/document/gutter.cljs`:

```clojure
(ns gremllm.renderer.ui.document.gutter
  (:require [gremllm.renderer.state.session :as session-state]
            [gremllm.renderer.ui.elements :as e]))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- render-bar [topic active? color]
  (let [topic-id (:id topic)
        anchor-text (get-in topic [:anchor :text])]
    [:button.session-bar-target
     {:aria-label   (str "Session: " (truncate anchor-text 40))
      :aria-pressed (if active? "true" "false")
      :data-topic-id topic-id
      :style {:--bar-color color}
      :on {:click     [[:effects/stop-propagation]
                       [:topic.actions/set-active topic-id]]
           :mouseenter [[:effects/save session-state/hovered-bar-topic-id-path topic-id]]
           :mouseleave [[:effects/save session-state/hovered-bar-topic-id-path nil]]}}
     [:div.session-bar
      {:class (if active? "session-bar--active" "session-bar--inactive")
       :style {:background-color color}}]]))

(defn render-gutter
  "Renders the session gutter with margin bars for all anchored topics."
  [topics-map active-topic-id]
  (let [anchored (session-state/anchored-topics-sorted topics-map)]
    [e/session-gutter
     (for [topic anchored]
       (let [active? (= (:id topic) active-topic-id)
             color   (session-state/color-for-topic topics-map (:id topic))]
         ^{:key (:id topic)}
         (render-bar topic active? color)))]))

(defn sync!
  "Measures anchor block rects in the article DOM and sets top/height
   on the corresponding bar buttons in the gutter.
   Positioning note: article and gutter are flex siblings inside the
   same scrolling .document-panel, so they share the same top edge.
   getBoundingClientRect returns viewport-relative coords; subtracting
   article-rect.top from block-rect.top gives an offset that works
   for both article-relative and gutter-relative positioning."
  [gutter article topics-map]
  (let [block-selector "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,table"
        blocks (.querySelectorAll article block-selector)
        article-rect (.getBoundingClientRect article)]
    (doseq [btn (.querySelectorAll gutter ".session-bar-target")]
      (let [topic-id (.getAttribute btn "data-topic-id")
            topic (get topics-map topic-id)
            anchor (:anchor topic)]
        (when anchor
          (let [start-idx (get-in anchor [:locator :start-block :index])
                end-idx   (get-in anchor [:locator :end-block :index])
                start-el  (some #(when (= (str start-idx)
                                          (.getAttribute % "data-grem-block-index"))
                                   %)
                                (array-seq blocks))
                end-el    (some #(when (= (str end-idx)
                                          (.getAttribute % "data-grem-block-index"))
                                  %)
                                (array-seq blocks))]
            (when (and start-el end-el)
              (let [start-rect (.getBoundingClientRect start-el)
                    end-rect   (.getBoundingClientRect end-el)
                    top        (- (.-top start-rect) (.-top article-rect))
                    height     (- (+ (.-top end-rect) (.-height end-rect))
                                 (.-top start-rect))]
                (set! (.. btn -style -top) (str top "px"))
                (set! (.. btn -style -height) (str height "px"))))))))))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/ui/document/gutter.cljs test/gremllm/renderer/ui/document/gutter_test.cljs src/gremllm/renderer/ui/elements.cljs
git commit -m "feat(gutter): session margin bar rendering and DOM position sync"
```

---

## Task 9: Anchor + Preview Highlights

**Files:**
- Modify: `src/gremllm/renderer/ui/document/highlights.cljs`
- Test: `test/gremllm/renderer/ui/document/highlights_test.cljs`

- [ ] **Step 1: Write the failing tests**

In `test/gremllm/renderer/ui/document/highlights_test.cljs`, add tests for the new registries. Read the existing test file first to match its setup pattern.

```clojure
;; Add to the existing test file:

(deftest sync-anchor-highlight-test
  (testing "sync-anchor! registers a highlight for anchor text"
    ;; This test depends on the jsdom setup used by existing highlight tests.
    ;; The function should be callable with an article element and anchor text.
    ;; Verify it creates a "anchor" entry in CSS.highlights.
    ))
```

Note: The existing highlight tests use `flatten-article` and `locate-range-in-flat-text` which are pure functions testable without a real DOM. The `sync!` and `sync-anchor!` functions require DOM access — test the pure helpers, verify the integration path manually.

- [ ] **Step 2: Extend highlights.cljs with anchor and preview registries**

In `src/gremllm/renderer/ui/document/highlights.cljs`, add:

```clojure
(def ^:private anchor-highlight-name "anchor")
(def ^:private anchor-preview-highlight-name "anchor-preview")

(defn sync-anchor!
  "Registers the anchor highlight for the active session's anchor text."
  [article anchor-text]
  (if (and article (seq anchor-text))
    (let [index (flatten-article article)
          hl    (js/Highlight.)]
      (when-let [range-info (locate-range-in-flat-text index anchor-text)]
        (.add hl (make-range range-info)))
      (.set js/CSS.highlights anchor-highlight-name hl))
    (.delete js/CSS.highlights anchor-highlight-name)))

(defn sync-anchor-preview!
  "Registers the preview highlight for a hovered bar's anchor text."
  [article preview-anchor-text]
  (if (and article (seq preview-anchor-text))
    (let [index (flatten-article article)
          hl    (js/Highlight.)]
      (when-let [range-info (locate-range-in-flat-text index preview-anchor-text)]
        (.add hl (make-range range-info)))
      (.set js/CSS.highlights anchor-preview-highlight-name hl))
    (.delete js/CSS.highlights anchor-preview-highlight-name)))

(defn clear-all!
  "Removes all highlight registries. Call on article unmount."
  []
  (.delete js/CSS.highlights highlight-name)
  (.delete js/CSS.highlights anchor-highlight-name)
  (.delete js/CSS.highlights anchor-preview-highlight-name))
```

- [ ] **Step 3: Update `clear!` references**

In `document.cljs`, change `highlights/clear!` to `highlights/clear-all!` in `on-render-sync`.

- [ ] **Step 4: Run tests**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/ui/document/highlights.cljs src/gremllm/renderer/ui/document.cljs test/gremllm/renderer/ui/document/highlights_test.cljs
git commit -m "feat(highlights): add anchor and anchor-preview highlight registries"
```

---

## Task 10: Popover — Two-Button Layout

**Files:**
- Modify: `src/gremllm/renderer/ui.cljs`

- [ ] **Step 1: Replace inline popover button with two-action popover**

In `src/gremllm/renderer/ui.cljs`, replace the single `[:button "Add excerpt"]` block (lines 45-59) with:

```clojure
(when popover-pos
  [:div.selection-popover
   {:style {:position      "absolute"
            :top           (str (:top popover-pos) "px")
            :left          (str (:left popover-pos) "px")
            :z-index       5}}
   [:button {:style {:background    "var(--pico-primary)"
                     :color         "var(--pico-primary-inverse)"
                     :padding       "4px 8px"
                     :border-radius "4px"
                     :font-size     "0.85rem"
                     :border        "none"
                     :cursor        "pointer"}
             :on {:mousedown [[:effects/stop-propagation]]
                  :click     [[:topic.actions/start-session-from-capture]]}}
    "Start session"]
   [:button {:style {:padding       "4px 8px"
                     :border-radius "4px"
                     :font-size     "0.85rem"
                     :border        "none"
                     :cursor        (if active-topic-id "pointer" "default")
                     :opacity       (if active-topic-id 1 0.5)}
             :disabled (nil? active-topic-id)
             :on {:mousedown [[:effects/stop-propagation]]
                  :click     [[:excerpt.actions/add]]}}
    "Add excerpt"]])
```

Note: `start-session-from-capture` was implemented and registered in Task 4. It reads the captured selection state, builds a `DocumentExcerpt` anchor, and delegates to `start-from-selection`.

- [ ] **Step 2: Add popover CSS**

In `resources/public/index.html`:

```css
.selection-popover {
    display: flex;
    gap: 4px;
}
```

- [ ] **Step 3: Run the app and verify manually**

Run: `npm run dev`
- Open a document
- Select text
- Verify two buttons appear: "Start session" and "Add excerpt"
- "Add excerpt" should be disabled (dimmed) when no session is active
- Click "Start session" — verify session is created and bar appears

- [ ] **Step 4: Commit**

```bash
git add src/gremllm/renderer/ui.cljs resources/public/index.html
git commit -m "feat(popover): two-action popover with Start session and Add excerpt"
```

---

## Task 11: Layout Integration — Wire Gutter and Highlights into Document Panel

**Files:**
- Modify: `src/gremllm/renderer/ui.cljs`
- Modify: `src/gremllm/renderer/ui/document.cljs`

- [ ] **Step 1: Update `render-app-layout` to pass session data and render gutter**

In `src/gremllm/renderer/ui.cljs`, update the state extraction and layout:

```clojure
(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.state.session :as session-state]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.welcome :as welcome-ui]
            [gremllm.renderer.ui.document :as document-ui]
            [gremllm.renderer.ui.document.gutter :as gutter]
            [gremllm.renderer.ui.elements :as e]))

(defn- render-app-layout [state]
  (let [document-content   (document-state/get-content state)
        pending-diffs      (topic-state/get-pending-diffs state)
        active-topic-id    (topic-state/get-active-topic-id state)
        topics-map         (topic-state/get-topics-map state)
        captured           (excerpt-state/get-captured state)
        anchor             (excerpt-state/get-anchor state)
        popover-pos        (excerpt-state/popover-position captured anchor)
        excerpts           (topic-state/get-excerpts state)
        active-topic       (topic-state/get-active-topic state)
        hovered-topic-id   (session-state/get-hovered-bar-topic-id state)
        hovered-topic      (when hovered-topic-id (get topics-map hovered-topic-id))
        active-color       (when active-topic-id
                             (session-state/color-for-topic topics-map active-topic-id))
        preview-color      (when hovered-topic-id
                             (session-state/color-for-topic topics-map hovered-topic-id))]
    [e/app-layout
     ;; Zone 1: Document panel (with gutter)
     [e/document-panel {:on    {:scroll    [[:excerpt.actions/dismiss-popover]]
                                :mousedown [[:excerpt.actions/dismiss-popover]]}
                        :style {:--active-session-color  (or active-color "transparent")
                                :--preview-session-color (or preview-color "transparent")}}
      (document-ui/render-document document-content pending-diffs excerpts
                                   {:active-anchor-text   (get-in active-topic [:anchor :text])
                                    :preview-anchor-text  (get-in hovered-topic [:anchor :text])
                                    :topics-map           topics-map})
      (gutter/render-gutter topics-map active-topic-id)
      ;; Popover
      (when popover-pos
        [:div.selection-popover
         {:style {:position "absolute"
                  :top      (str (:top popover-pos) "px")
                  :left     (str (:left popover-pos) "px")
                  :z-index  5}}
         [:button {:style {:background    "var(--pico-primary)"
                           :color         "var(--pico-primary-inverse)"
                           :padding       "4px 8px"
                           :border-radius "4px"
                           :font-size     "0.85rem"
                           :border        "none"
                           :cursor        "pointer"}
                   :on {:mousedown [[:effects/stop-propagation]]
                        :click     [[:topic.actions/start-session-from-capture]]}}
          "Start session"]
         [:button {:style {:padding       "4px 8px"
                           :border-radius "4px"
                           :font-size     "0.85rem"
                           :border        "none"
                           :cursor        (if active-topic-id "pointer" "default")
                           :opacity       (if active-topic-id 1 0.5)}
                   :disabled (nil? active-topic-id)
                   :on {:mousedown [[:effects/stop-propagation]]
                        :click     [[:excerpt.actions/add]]}}
          "Add excerpt"]])]

     ;; Zone 2: Chat panel
     [e/chat-panel
      (let [messages (topic-state/get-messages state)
            awaiting-response? (and (loading-state/loading? state active-topic-id)
                                    (= :user (:type (peek messages))))]
        (chat-ui/render-chat-area messages awaiting-response?
                                  {:active-topic active-topic
                                   :active-topic-id active-topic-id}))

      (when active-topic-id
        (chat-ui/render-input-form
          {:input-value         (form-state/get-user-input state)
           :loading?            (loading-state/loading? state active-topic-id)
           :pending-attachments (form-state/get-pending-attachments state)
           :excerpts            excerpts
           :shell?              (nil? (topic-state/get-acp-session-id state active-topic-id))}))]]))
```

- [ ] **Step 2: Update `document.cljs` to pass session data through on-render-sync**

In `src/gremllm/renderer/ui/document.cljs`, update `on-render-sync` to also sync anchor highlights and gutter positions:

```clojure
(defn- on-render-sync [content excerpts session-opts]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear-all!)
      (let [article node
            gutter-el (some-> article .-parentElement (.querySelector ".session-gutter"))]
        (locator/sync-block-metadata! article content)
        (highlights/sync! article excerpts)
        (highlights/sync-anchor! article (:active-anchor-text session-opts))
        (highlights/sync-anchor-preview! article (:preview-anchor-text session-opts))
        (when gutter-el
          (gutter/sync! gutter-el article (:topics-map session-opts)))))))
```

Update `render-document` to accept and pass the session opts:

```clojure
(defn render-document [content pending-diffs excerpts session-opts]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                  {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render (on-render-sync content excerpts session-opts)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document open."]
     [:button {:on {:click [[:document.actions/pick]]}}
      "Open..."]]))
```

- [ ] **Step 3: Run tests**

Run: `npm run test:ci`
Expected: PASS (update any document_test.cljs tests that call `render-document` with the old 3-arg signature to pass a 4th arg `{}`)

- [ ] **Step 4: Run the app and verify visually**

Run: `npm run dev`
- Open a document
- Select text → click "Start session"
- Verify: margin bar appears in gutter, anchor text highlighted, chat panel shows session
- Select different text → create a second session
- Verify: second bar with different color, both bars visible
- Click an inactive bar → verify session switch (active bar brightens, anchor highlight moves)
- Hover an inactive bar → verify preview highlight appears

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/ui.cljs src/gremllm/renderer/ui/document.cljs
git commit -m "feat(layout): wire gutter, anchor highlights, and two-zone layout"
```

---

## Task 12: Chat Panel — Empty State and Shell Session UI

**Files:**
- Modify: `src/gremllm/renderer/ui/chat.cljs`
- Test: `test/gremllm/renderer/ui/chat_test.cljs`

- [ ] **Step 1: Write tests for new chat states**

In `test/gremllm/renderer/ui/chat_test.cljs`, add:

```clojure
;; Read the existing test file first to match its setup patterns.
;; Add tests for the new empty-state and shell-session renders.

(deftest no-active-session-prompt-test
  (testing "renders prompt when no active session"
    (let [hiccup (chat-ui/render-chat-area [] false {:active-topic nil
                                                      :active-topic-id nil})]
      (is (some #(and (string? %) (clojure.string/includes? % "Select text"))
                (flatten hiccup))))))

(deftest shell-session-shows-anchor-context-test
  (testing "shell session shows anchor text context"
    (let [topic {:id "t1" :name "T" :anchor {:id "e1" :text "sample anchor text" :locator {}}}
          hiccup (chat-ui/render-chat-area [] false {:active-topic topic
                                                      :active-topic-id "t1"})]
      (is (some #(and (string? %) (clojure.string/includes? % "sample anchor text"))
                (flatten hiccup))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test:ci`
Expected: FAIL — `render-chat-area` doesn't accept the new session opts.

- [ ] **Step 3: Implement chat panel states**

In `src/gremllm/renderer/ui/chat.cljs`, update `render-chat-area`:

```clojure
(defn render-chat-area [messages awaiting-response? session-opts]
  (let [{:keys [active-topic active-topic-id]} session-opts]
    (cond
      (nil? active-topic-id)
      [e/chat-area
       [:div {:style {:display "flex"
                      :align-items "center"
                      :justify-content "center"
                      :height "100%"
                      :color "var(--pico-muted-color)"
                      :font-style "italic"}}
        "Select text in the document to start a session."]]

      (and active-topic (nil? (get-in active-topic [:session :id])))
      [e/chat-area
       [:div {:style {:padding "var(--pico-spacing)"}}
        [:blockquote {:style {:border-left-color "var(--pico-primary)"
                              :font-size "0.9rem"
                              :opacity 0.8}}
         (get-in active-topic [:anchor :text])]
        [:p {:style {:color "var(--pico-muted-color)" :font-size "0.85rem"}}
         "Session not connected."]]]

      :else
      ;; Existing message rendering
      [e/chat-area {:replicant/on-render (scroll-to-bottom-on-update messages)}
       (map-indexed (fn [i msg] ^{:key i} (render-message msg)) messages)
       (when awaiting-response?
         [:div.tool-status-indicator "..."])])))
```

- [ ] **Step 4: Update `render-input-form` for shell sessions**

Add a `:shell?` key to the input form props. When `shell?` is true, render the composer as disabled:

```clojure
(defn render-input-form [{:keys [input-value loading? pending-attachments excerpts shell?]}]
  [:footer {:style {:padding "0 var(--pico-spacing) var(--pico-spacing)"}}
   ;; Existing excerpt chips and attachment indicators
   (when (seq excerpts)
     (render-composer-excerpts excerpts))
   (when (seq pending-attachments)
     (render-attachment-indicator pending-attachments))
   [:form {:on {:submit [[:effects/prevent-default] [:form.actions/submit]]}}
    [:textarea.chat-input
     {:placeholder (if shell? "Session not connected" "Type a message...")
      :disabled    (or loading? shell?)
      :value       input-value
      :on          {:input  [[:form.actions/update-input :event.target/value]]
                    :keydown [[:form.actions/handle-submit-keys :event/key-pressed]]}}]]])
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/ui/chat.cljs test/gremllm/renderer/ui/chat_test.cljs
git commit -m "feat(chat): empty state, shell session context, and disabled composer"
```

---

## Task 13: Action Registry Cleanup

**Files:**
- Modify: `src/gremllm/renderer/actions.cljs`
- Modify: `src/gremllm/renderer/actions/ui.cljs`

- [ ] **Step 1: Remove dead registrations from actions.cljs**

In `src/gremllm/renderer/actions.cljs`, remove:

```clojure
;; Remove these lines:
(nxr/register-action! :ui.actions/toggle-nav ui/toggle-nav)
(nxr/register-action! :topic.actions/begin-rename topic/begin-rename)
(nxr/register-action! :topic.actions/commit-rename topic/commit-rename)
(nxr/register-action! :topic.actions/handle-rename-keys topic/handle-rename-keys)
(nxr/register-action! :ui.actions/exit-topic-rename-mode ...)
```

Remove the `topics-ui` require from `gremllm.renderer.ui` if not already done.

- [ ] **Step 2: Remove `toggle-nav` from actions/ui.cljs**

In `src/gremllm/renderer/actions/ui.cljs`, remove the `toggle-nav` function.

- [ ] **Step 3: Remove rename functions from actions/topic.cljs**

In `src/gremllm/renderer/actions/topic.cljs`, remove: `begin-rename`, `commit-rename`, `handle-rename-keys`, `set-name`. These are dead code with the nav overlay removed.

- [ ] **Step 4: Remove orphaned `renaming-topic-id-path` from state/ui.cljs**

In `src/gremllm/renderer/state/ui.cljs`, remove `renaming-topic-id-path` and `renaming-topic-id` — they were only used by the rename functions being deleted in Step 3. The file should become empty or contain only the ns declaration. If it becomes empty, delete it.

```clojure
(ns gremllm.renderer.state.ui)
```

Also remove the `ui-state` require from `src/gremllm/renderer/actions/topic.cljs` if it's no longer referenced.

- [ ] **Step 5: Remove dead tests**

In `test/gremllm/renderer/actions/topic_test.cljs`, remove `commit-rename-test`.

- [ ] **Step 6: Run tests**

Run: `npm run test:ci`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -u
git commit -m "chore: remove dead nav overlay and rename action registrations"
```

---

## Task 14: Verify Session Color on Switch

No code changes — this is a manual verification checkpoint for the highlight coloring approach.

The clear-and-reset strategy in `sync-anchor!` and `sync-anchor-preview!` (Task 9) re-creates the highlight registry on every `on-render-sync` call. The `::highlight(anchor)` CSS rule reads `--active-session-color` from `.document-panel`, which Task 11 sets via inline `:style` on each Replicant re-render. This combination means the highlight color updates automatically on session switch without relying on browser repaint of CSS variables mid-lifecycle.

- [ ] **Step 1: Verify session color switching**

Run: `npm run dev`
- Create two sessions with different anchor text
- Switch between them by clicking bars
- Verify the anchor highlight color changes to match the active session's bar color
- Hover over an inactive bar — verify the preview highlight uses the hovered session's color

If the `::highlight()` pseudo-element does not pick up variable changes despite the clear-and-reset, the fallback is to inline the color directly into `sync-anchor!` / `sync-anchor-preview!` via `Highlight` constructor options or a dynamic `<style>` element. This should not be needed.

---

## Task 15: End-to-End Manual Verification

No code changes — this is a manual testing checkpoint.

- [ ] **Step 1: Fresh start**

Run: `npm run dev`
- Verify the app opens with two-column layout (document + chat), no nav strip
- Verify the gutter is present but empty (no bars)
- Verify the chat panel shows "Select text in the document to start a session."
- Verify the composer is not shown

- [ ] **Step 2: Create first session**

- Open a markdown file
- Select text in the document
- Verify a two-button popover appears: "Start session" (enabled) and "Add excerpt" (disabled/dimmed)
- Click "Start session"
- Verify: margin bar appears in gutter with warm orange color (color 1)
- Verify: anchor text gets subtle highlight in the document (orange at ~12%)
- Verify: chat panel switches to show anchor text as context + "Session not connected"
- Verify: composer appears but is disabled

- [ ] **Step 3: Create second session**

- Select different text
- Verify "Add excerpt" is now enabled (there's an active session)
- Click "Start session" again
- Verify: second bar appears in teal (color 2)
- Verify: anchor highlight moves to the new session's text
- Verify: first bar dims to 0.35 opacity

- [ ] **Step 4: Bar interaction**

- Click the first (inactive) bar
- Verify: it brightens to full opacity, second bar dims
- Verify: anchor highlight moves to the first session's text
- Verify: chat panel shows first session's context
- Hover over the inactive (second) bar
- Verify: it goes to 0.70 opacity
- Verify: a subtle preview highlight appears on the second session's anchor text
- Move mouse away — preview highlight clears

- [ ] **Step 5: Accessibility**

- Tab to focus the gutter buttons
- Verify they're focusable and show focus indicator
- Press Enter/Space on a bar — verify it switches sessions
- Check aria-label reads correctly (e.g., "Session: launched on a Tuesday...")
- Check aria-pressed reflects active state

- [ ] **Step 6: Edge cases**

- Create 6+ sessions — verify color wraps back to color 1
- Restart the app — verify sessions are gone (shell sessions don't persist)
- Open a document that has old topics from before Slice 1 — verify no bars appear and no session is auto-activated
