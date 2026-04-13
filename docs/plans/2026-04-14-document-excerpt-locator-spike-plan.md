# Document Excerpt Locator Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gather hard evidence about which revision-bound locator fields we can reliably derive for rendered markdown selections before drafting the `DocumentExcerpt.locator` spec.

**Architecture:** Keep the spike renderer-only. Parse the current markdown text through the same bundled `markdown-it` pipeline that powers `nextjournal/markdown`, derive per-block metadata from token maps, tag the rendered article DOM with that metadata, and log selection diagnostics during capture. Do not change persisted schemas or ACP prompt shape in this spike.

**Tech Stack:** ClojureScript, `nextjournal/markdown`, bundled `markdown-it` (`/js/markdown`), Electron renderer, `cljs.test`

---

## File Map

- Create: `src/gremllm/renderer/ui/document/locator.cljs`
  Purpose: Pure markdown block metadata helpers plus thin DOM helpers for tagging rendered blocks and deriving selection diagnostics.
- Create: `test/gremllm/renderer/ui/document/locator_test.cljs`
  Purpose: Unit tests for block metadata extraction and selection-diagnostic pure helpers.
- Create: `test/gremllm/renderer/actions/document_test.cljs`
  Purpose: Unit tests for document replacement invalidation behavior.
- Modify: `src/gremllm/renderer/ui/document.cljs`
  Purpose: Run block-tag sync on render before existing highlight sync.
- Modify: `src/gremllm/renderer/actions.cljs`
  Purpose: Extend the `:event/text-selection` placeholder to return derived locator diagnostics.
- Modify: `src/gremllm/renderer/actions/excerpt.cljs`
  Purpose: Save and log locator diagnostics during capture, and clear them on dismiss.
- Modify: `src/gremllm/renderer/state/excerpt.cljs`
  Purpose: Add ephemeral state path for latest locator diagnostics.
- Modify: `src/gremllm/renderer/actions/document.cljs`
  Purpose: Clear staged selections and live capture state on `document.actions/set-content`.
- Modify: `test/gremllm/renderer/actions/excerpt_test.cljs`
  Purpose: Lock in the expanded capture payload shape.
- Create during execution: `docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md`
  Purpose: Record the observed evidence from the manual spike so the later spec is grounded in real output.

## Scope Guardrails

- This plan is for the spike only.
- Do not change `schema.cljs` or persist new locator fields yet.
- Do not change `main.actions.acp/prompt-content-blocks` yet.
- Do not rewrite the highlight anchoring logic yet.
- Keep all new logging prefixed with `[excerpt-locator-spike]` so it is easy to remove after findings are captured.

### Task 1: Extract Revision-Bound Block Metadata From Markdown

**Files:**
- Create: `src/gremllm/renderer/ui/document/locator.cljs`
- Test: `test/gremllm/renderer/ui/document/locator_test.cljs`

- [ ] **Step 1: Write the failing tests for block metadata extraction**

Add this test file:

```clojure
(ns gremllm.renderer.ui.document.locator-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.locator :as locator]))

(deftest block-records-test
  (let [markdown "# Title\n\nPara **bold** text\n\n- first\n- second\n\n```clj\n(+ 1 2)\n```\n"
        blocks   (locator/block-records markdown)]
    (testing "extracts block kind, 1-based index, and inclusive 1-based line spans"
      (is (= [{:kind :heading    :index 1 :start-line 1 :end-line 1 :text "Title"}
              {:kind :paragraph  :index 2 :start-line 3 :end-line 3 :text "Para bold text"}
              {:kind :list-item  :index 3 :start-line 5 :end-line 5 :text "first"}
              {:kind :list-item  :index 4 :start-line 6 :end-line 6 :text "second"}
              {:kind :code-block :index 5 :start-line 8 :end-line 10 :text "(+ 1 2)\n"}]
             (mapv #(select-keys % [:kind :index :start-line :end-line :text]) blocks)))))

  (testing "returns an empty vector for blank input"
    (is (= [] (locator/block-records "")))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run test:ci
```

Expected:
- FAIL in `gremllm.renderer.ui.document.locator-test`
- Error mentions missing namespace or missing `block-records`

- [ ] **Step 3: Write the minimal block metadata implementation**

Create `src/gremllm/renderer/ui/document/locator.cljs` with this implementation:

```clojure
(ns gremllm.renderer.ui.document.locator
  (:require ["/js/markdown" :as markdown-js]))

(def ^:private block-open->kind
  {"heading_open"    :heading
   "paragraph_open"  :paragraph
   "list_item_open"  :list-item
   "blockquote_open" :blockquote
   "fence"           :code-block
   "code_block"      :code-block
   "table_open"      :table})

(defn- token-kind [^js token]
  (get block-open->kind (.-type token)))

(defn- inline-token? [^js token]
  (= "inline" (.-type token)))

(defn- token-map->line-span [^js token]
  (when-let [m (some-> (.-map token) js->clj)]
    (let [[start end] m]
      {:start-line (inc start)
       :end-line   end})))

(defn- inline-text [^js token]
  (->> (array-seq (or (.-children token) #js []))
       (map (fn [^js child]
              (case (.-type child)
                "softbreak" " "
                (.-content child))))
       (apply str)))

(defn- token-text [tokens idx ^js token]
  (case (.-type token)
    ("fence" "code_block") (.-content token)
    (some->> (drop (inc idx) tokens)
             (filter inline-token?)
             (filter #(= (js->clj (.-map %)) (js->clj (.-map token))))
             first
             inline-text)
    ""))

(defn block-records [markdown-text]
  (let [tokens (vec (array-seq (markdown-js/tokenize #js {:disable_inline_formulas false}
                                                     (or markdown-text ""))))]
    (->> tokens
         (map-indexed
           (fn [idx ^js token]
             (when-let [kind (token-kind token)]
               (when-let [{:keys [start-line end-line]} (token-map->line-span token)]
                 {:kind       kind
                  :index      nil
                  :start-line start-line
                  :end-line   end-line
                  :text       (token-text tokens idx token)}))))
         (keep identity)
         (map-indexed (fn [idx block] (assoc block :index (inc idx))))
         vec)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
npm run test:ci
```

Expected:
- PASS for `gremllm.renderer.ui.document.locator-test`
- Existing markdown/document tests still pass

- [ ] **Step 5: Commit the extraction helper**

Run:

```bash
git add src/gremllm/renderer/ui/document/locator.cljs test/gremllm/renderer/ui/document/locator_test.cljs
git commit -m "feat(locator): extract markdown block metadata for selection spike"
```

### Task 2: Add Pure Selection-Diagnostic Helpers

**Files:**
- Modify: `src/gremllm/renderer/ui/document/locator.cljs`
- Test: `test/gremllm/renderer/ui/document/locator_test.cljs`

- [ ] **Step 1: Write the failing tests for normalized offsets and cross-block behavior**

Append these tests to `test/gremllm/renderer/ui/document/locator_test.cljs`:

```clojure
(deftest selection-debug-test
  (let [heading-block   {:kind :heading :index 1 :start-line 1 :end-line 1 :text "Title"}
        paragraph-block {:kind :paragraph :index 2 :start-line 3 :end-line 3 :text "Para bold text"}]
    (testing "same-block selections get start/end offsets"
      (is (= {:block-kind  :paragraph
              :block-index 2
              :block-start-line 3
              :block-end-line 3
              :start-offset 5
              :end-offset 14}
             (locator/selection-debug paragraph-block paragraph-block "bold text"))))

    (testing "cross-block selections omit offsets and keep the start block as primary"
      (is (= {:block-kind  :heading
              :block-index 1
              :block-start-line 1
              :block-end-line 1}
             (locator/selection-debug heading-block paragraph-block "Title\nPara"))))

    (testing "blank selected text returns nil"
      (is (nil? (locator/selection-debug paragraph-block paragraph-block ""))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run test:ci
```

Expected:
- FAIL in `selection-debug-test`
- Error mentions missing `selection-debug`

- [ ] **Step 3: Implement the pure diagnostic helpers**

Add these functions to `src/gremllm/renderer/ui/document/locator.cljs`:

```clojure
(defn- text-offsets [block-text selected-text]
  (let [start (.indexOf (or block-text "") (or selected-text ""))]
    (when (and (seq selected-text) (not (neg? start)))
      {:start-offset start
       :end-offset   (+ start (count selected-text))})))

(defn selection-debug [start-block end-block selected-text]
  (when (seq selected-text)
    (let [base {:block-kind       (:kind start-block)
                :block-index      (:index start-block)
                :block-start-line (:start-line start-block)
                :block-end-line   (:end-line start-block)}]
      (if (= (:index start-block) (:index end-block))
        (merge base (or (text-offsets (:text start-block) selected-text) {}))
        base))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
npm run test:ci
```

Expected:
- PASS for `block-records-test` and `selection-debug-test`
- No regressions in existing renderer tests

- [ ] **Step 5: Commit the pure diagnostic helpers**

Run:

```bash
git add src/gremllm/renderer/ui/document/locator.cljs test/gremllm/renderer/ui/document/locator_test.cljs
git commit -m "feat(locator): add pure selection diagnostic helpers"
```

### Task 3: Wire Renderer Diagnostics Into Selection Capture

**Files:**
- Modify: `src/gremllm/renderer/ui/document/locator.cljs`
- Modify: `src/gremllm/renderer/ui/document.cljs`
- Modify: `src/gremllm/renderer/actions.cljs`
- Modify: `src/gremllm/renderer/actions/excerpt.cljs`
- Modify: `src/gremllm/renderer/state/excerpt.cljs`
- Test: `test/gremllm/renderer/actions/excerpt_test.cljs`

- [ ] **Step 1: Write the failing test for expanded capture payload**

Update `test/gremllm/renderer/actions/excerpt_test.cljs` to add locator diagnostics:

```clojure
(def locator-debug
  {:block-kind :paragraph
   :block-index 2
   :block-start-line 3
   :block-end-line 3
   :start-offset 5
   :end-offset 14})

(def composite-selection
  {:selection schema-test/single-word-selection
   :anchor anchor-context
   :locator-debug locator-debug})

(deftest capture-test
  (testing "valid composite saves selection, anchor, and locator debug"
    (let [result (excerpt/capture {} composite-selection)]
      (is (= [:effects/save excerpt-state/captured-path schema-test/single-word-selection]
             (nth result 0)))
      (is (= [:effects/save excerpt-state/anchor-path anchor-context]
             (nth result 1)))
      (is (= [:effects/save excerpt-state/locator-debug-path locator-debug]
             (nth result 2))))))

(deftest dismiss-popover-test
  (testing "clears captured-path, anchor-path, and locator-debug-path"
    (is (= [[:effects/save excerpt-state/captured-path nil]
            [:effects/save excerpt-state/anchor-path nil]
            [:effects/save excerpt-state/locator-debug-path nil]]
           (excerpt/dismiss-popover {})))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run test:ci
```

Expected:
- FAIL in `gremllm.renderer.actions.excerpt-test`
- Error mentions missing `locator-debug-path` or mismatched effect count

- [ ] **Step 3: Implement DOM tagging, capture diagnostics, and console logging**

Apply these focused changes:

```clojure
;; src/gremllm/renderer/state/excerpt.cljs
(def locator-debug-path [:excerpt :locator-debug])
```

```clojure
;; src/gremllm/renderer/ui/document/locator.cljs
(def ^:private block-selector "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,table")

(defn sync-block-metadata! [article markdown-text]
  (let [blocks    (block-records markdown-text)
        elements  (array-seq (.querySelectorAll article block-selector))]
    (doseq [[el block] (map vector elements blocks)]
      (.setAttribute el "data-grem-block-kind" (name (:kind block)))
      (.setAttribute el "data-grem-block-index" (str (:index block)))
      (.setAttribute el "data-grem-block-start-line" (str (:start-line block)))
      (.setAttribute el "data-grem-block-end-line" (str (:end-line block))))))

(defn selection-debug-from-dom [article sel]
  (let [range          (.getRangeAt sel 0)
        start-element  (some-> (.-startContainer range) .-parentElement (.closest block-selector))
        end-element    (some-> (.-endContainer range) .-parentElement (.closest block-selector))
        parse-block    (fn [el]
                         (when el
                           {:kind       (keyword (.getAttribute el "data-grem-block-kind"))
                            :index      (js/parseInt (.getAttribute el "data-grem-block-index"))
                            :start-line (js/parseInt (.getAttribute el "data-grem-block-start-line"))
                            :end-line   (js/parseInt (.getAttribute el "data-grem-block-end-line"))
                            :text       (.-textContent el)}))
        start-block    (parse-block start-element)
        end-block      (parse-block end-element)]
    (when (and start-block end-block)
      {:selection-direction (or (.-direction sel) "unknown")
       :anchor-node         (some-> (.-anchorNode sel) .-nodeName)
       :anchor-offset       (.-anchorOffset sel)
       :focus-node          (some-> (.-focusNode sel) .-nodeName)
       :focus-offset        (.-focusOffset sel)
       :common-ancestor     (some-> (.-commonAncestorContainer range) .-nodeName)
       :locator             (selection-debug start-block end-block (.toString sel))})))
```

```clojure
;; src/gremllm/renderer/ui/document.cljs
(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.diffs :as diffs]
            [gremllm.renderer.ui.document.highlights :as highlights]
            [gremllm.renderer.ui.document.locator :as locator]))

(defn- on-render-sync [content staged-selections]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear!)
      (do
        (locator/sync-block-metadata! node content)
        (highlights/sync! node staged-selections)))))

(defn render-document [content pending-diffs staged-selections]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                  {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render (on-render-sync content staged-selections)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color "var(--pico-muted-color)" :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
```

```clojure
;; src/gremllm/renderer/actions.cljs
;; add this require to the existing ns form
[gremllm.renderer.ui.document.locator :as locator]

(nxr/register-placeholder! :event/text-selection
  (fn [{:replicant/keys [dom-event]}]
    (let [sel     (js/document.getSelection)
          panel   (when dom-event (.. dom-event -target (closest ".document-panel")))
          article (when panel (.querySelector panel "article"))]
      (when (and sel (pos? (.-rangeCount sel)) (not (.-isCollapsed sel)))
        {:selection     (codec/captured-selection-from-dom sel)
         :anchor        (when panel (codec/anchor-context-from-dom panel))
         :locator-debug (when article (locator/selection-debug-from-dom article sel))}))))
```

```clojure
;; src/gremllm/renderer/actions/excerpt.cljs
(defn capture [_state {:keys [selection anchor locator-debug]}]
  (if selection
    (do
      (when locator-debug
        (js/console.log "[excerpt-locator-spike]" (clj->js locator-debug)))
      [[:effects/save excerpt-state/captured-path selection]
       [:effects/save excerpt-state/anchor-path anchor]
       [:effects/save excerpt-state/locator-debug-path locator-debug]])
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]
   [:effects/save excerpt-state/locator-debug-path nil]])
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
npm run test:ci
```

Expected:
- PASS in `gremllm.renderer.actions.excerpt-test`
- Existing `render-document` and `markdown` tests still pass

- [ ] **Step 5: Commit the renderer wiring**

Run:

```bash
git add src/gremllm/renderer/ui/document/locator.cljs src/gremllm/renderer/ui/document.cljs src/gremllm/renderer/actions.cljs src/gremllm/renderer/actions/excerpt.cljs src/gremllm/renderer/state/excerpt.cljs test/gremllm/renderer/actions/excerpt_test.cljs
git commit -m "feat(locator): log selection diagnostics in renderer spike"
```

### Task 4: Clear Staged Selections On Document Replacement

**Files:**
- Modify: `src/gremllm/renderer/actions/document.cljs`
- Create: `test/gremllm/renderer/actions/document_test.cljs`

- [ ] **Step 1: Write the failing test for document replacement invalidation**

Create `test/gremllm/renderer/actions/document_test.cljs`:

```clojure
(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]))

(deftest set-content-test
  (let [state {:topics {"t1" {:id "t1" :staged-selections [{:id "a"}]}
                        "t2" {:id "t2" :staged-selections [{:id "b"}]}}
               :excerpt {:captured {:text "Dispatch"}
                         :anchor {:panel-scroll-top 20}
                         :locator-debug {:block-index 1}}}
        effects (document/set-content state "# Replaced")]
    (testing "saves the new content first"
      (is (= [:effects/save document-state/content-path "# Replaced"]
             (first effects))))

    (testing "clears staged selections for every topic"
      (is (some #{[:effects/save (topic-state/staged-selections-path "t1") []]} effects))
      (is (some #{[:effects/save (topic-state/staged-selections-path "t2") []]} effects)))

    (testing "dismisses live capture state"
      (is (some #{[:excerpt.actions/dismiss-popover]} effects)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run test:ci
```

Expected:
- FAIL in `gremllm.renderer.actions.document-test`
- Error mentions missing clear effects

- [ ] **Step 3: Implement simple renderer-side invalidation**

Update `src/gremllm/renderer/actions/document.cljs`:

```clojure
(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn set-content [state content]
  (let [topic-ids (keys (topic-state/get-topics-map state))]
    (into [[:effects/save document-state/content-path content]]
          (concat
            (map (fn [topic-id]
                   [:effects/save (topic-state/staged-selections-path topic-id) []])
                 topic-ids)
            [[:excerpt.actions/dismiss-popover]]))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
npm run test:ci
```

Expected:
- PASS in `gremllm.renderer.actions.document-test`
- Existing workspace/document tests still pass

- [ ] **Step 5: Commit the invalidation rule**

Run:

```bash
git add src/gremllm/renderer/actions/document.cljs test/gremllm/renderer/actions/document_test.cljs
git commit -m "feat(document): clear staged selections on document replacement"
```

### Task 5: Run The Manual Spike And Capture Findings

**Files:**
- Create: `docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md`

- [ ] **Step 1: Create the findings template**

Create `docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md`:

```markdown
# Document Excerpt Locator Spike Findings

**Date:** 2026-04-14
**Fixture:** `resources/gremllm-launch-log.md`

## Questions

1. Does one enclosing block give enough precision for normal selections?
2. What do we get for cross-block selections?
3. Are start/end offsets reliable inside a single rendered block?
4. Which fields are useful enough to carry into `DocumentExcerpt.locator`?

## Cases

### Single paragraph

- Console output:
- Observations:

### Mixed formatting inside one paragraph

- Console output:
- Observations:

### Cross-block selection

- Console output:
- Observations:

### List item

- Console output:
- Observations:

### Code block

- Console output:
- Observations:

### Table cell (if available)

- Console output:
- Observations:

## Recommendation For Spec Draft

- Keep:
- Drop:
- Open questions:
```

- [ ] **Step 2: Run the app with a disposable workspace fixture**

Run:

```bash
mkdir -p /tmp/gremllm-locator-spike
cp resources/gremllm-launch-log.md /tmp/gremllm-locator-spike/document.md
npm run dev
```

Expected:
- Electron app starts
- Dataspex opens
- Opening `/tmp/gremllm-locator-spike` shows the fixture document in the document panel

- [ ] **Step 3: Exercise the selection cases and record the real console output**

Manual steps:

```text
1. Select text wholly inside a paragraph.
2. Select mixed-format text inside one paragraph.
3. Select from a heading into the next block.
4. Select text inside a list item.
5. Select text inside a fenced code block.
6. If the fixture has no table, note "table not present" and stop there.
```

Expected:
- Each selection prints one `[excerpt-locator-spike]` console entry
- Each log includes `selection-direction`, boundary node names, common ancestor, and `locator`
- Same-block selections show `start-offset` / `end-offset`
- Cross-block selections omit offsets and keep the start block as primary

- [ ] **Step 4: Run regression tests after the manual spike**

Run:

```bash
npm run test:ci
```

Expected:
- PASS
- No test regressions from the spike plumbing

- [ ] **Step 5: Commit the findings**

Run:

```bash
git add docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md
git commit -m "docs(locator): record document excerpt locator spike findings"
```

## Self-Review Checklist

- `block-records` is the only source of parser-derived line spans in this spike.
- DOM tagging stays renderer-only and removable after the evidence pass.
- No task mutates `DocumentExcerpt` schema or ACP prompt shape.
- Invalidation is simple: every `document.actions/set-content` clears staged selections and live capture state.
- Findings are written down before spec drafting resumes.
