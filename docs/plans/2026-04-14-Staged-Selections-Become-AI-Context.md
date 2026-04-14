# S8 Implementation Plan — Staged Selections Become AI Context

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make document staged selections first-class AI context. They become durable `DocumentExcerpt` values, persist with the topic, travel with the user message into the ACP prompt as full excerpt text plus advisory locator metadata, clear only on prompt success, and render as compact references in the chat transcript.

**Architecture:** Introduce a durable `DocumentExcerpt` domain entity alongside the existing ephemeral `CapturedSelection`. Replace `StagedSelection` with `DocumentExcerpt`. Extend the renderer-side `selection-locator` to emit advisory `BlockRef` pairs (start + end, with optional offsets only for same-block). Restructure the user-submit → ACP send path to carry a structured user message `{:text :context {:excerpts [...]}}` through the renderer action, preload, IPC, and main-side prompt builder. Wire staging mutations through the topic mark-unsaved + auto-save path to close the S7.3 persistence gap. Clear staged selections only after the ACP prompt IPC resolves successfully.

**Tech Stack:** ClojureScript, Shadow-CLJS, Nexus (state mgmt), Replicant (UI), malli (schemas), Electron (IPC/preload), ACP SDK.

---

## Source References

**Spec:** `/Users/paul/Projects/gremllm/docs/specs/2026-04-14-s8-staged-selections-ai-context-design.md`
**Spike findings:** `/Users/paul/Projects/gremllm/docs/plans/2026-04-14-document-excerpt-locator-spike-findings.md`

**Existing files to modify:**
- `src/gremllm/schema.cljs` — schema defs
- `src/gremllm/schema/codec.cljs` — (probably unchanged; `strip-extra-keys-transformer` already protects persisted topics once `:context` is declared on `Message`)
- `src/gremllm/renderer/ui/document/locator.cljs` — block records + selection-locator pure fn + selection-locator-from-dom
- `src/gremllm/renderer/actions/excerpt.cljs` — `stage` action (builds `DocumentExcerpt` from capture + locator-hints)
- `src/gremllm/renderer/actions/topic.cljs` — `stage`, `unstage`, `clear-staged`, `auto-save` (wire mark-unsaved + auto-save; relax empty-messages guard)
- `src/gremllm/renderer/actions/ui.cljs` — `submit-messages` (attach `:context {:excerpts}`, pass message to send-prompt)
- `src/gremllm/renderer/actions/acp.cljs` — `send-prompt` renderer-side (accept message, pass structured payload through preload, clear staged on success)
- `src/gremllm/renderer/actions.cljs` — registration updates
- `src/gremllm/renderer/ui/document/highlights.cljs` — read `:text` directly off excerpt (not `(get-in [:selection :text])`)
- `src/gremllm/renderer/ui/chat.cljs` — compact References row under user-message bubble; staged-selection pills read new shape
- `resources/public/js/preload.js` — `acpPrompt` signature accepts message object
- `src/gremllm/main/core.cljs` — `acp/prompt` IPC handler accepts message object
- `src/gremllm/main/actions.cljs` — `:acp.effects/send-prompt` effect passes message through
- `src/gremllm/main/actions/acp.cljs` — `prompt-content-blocks` accepts structured message, builds references section

**Existing tests to extend:** `test/gremllm/schema_test.cljs`, `test/gremllm/renderer/actions/{excerpt,topic,ui,acp}_test.cljs`, `test/gremllm/main/actions/acp_test.cljs`, and add a locator test file under `test/gremllm/renderer/ui/document/`.

**Nexus placeholder pattern:** actions are pure `(fn [state & args] -> [[:effect ...] ...])`. Test them by calling the fn directly with a state map and asserting the returned vector.

---

## Task 1: Schema — Add `BlockRef` and `DocumentExcerpt`

**Files:**
- Modify: `src/gremllm/schema.cljs`
- Test: `test/gremllm/schema_test.cljs`

- [ ] **Step 1: Write failing tests**

Append to `test/gremllm/schema_test.cljs`:

```clojure
(deftest block-ref-test
  (testing "valid BlockRef"
    (is (m/validate schema/BlockRef
                    {:kind :paragraph
                     :index 2
                     :start-line 3
                     :end-line 3
                     :block-text-snippet "Our Gremllm launched on a Tuesday."})))
  (testing "missing required field fails"
    (is (not (m/validate schema/BlockRef
                         {:kind :paragraph :index 2 :start-line 3 :end-line 3})))))

(deftest document-excerpt-test
  (let [block {:kind :paragraph
               :index 2
               :start-line 3
               :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}]
    (testing "same-block excerpt with offsets"
      (is (m/validate schema/DocumentExcerpt
                      {:id "excerpt-abc"
                       :text "launched on a Tuesday"
                       :locator {:document-relative-path "document.md"
                                 :start-block block
                                 :end-block block
                                 :start-offset 4
                                 :end-offset 25}})))
    (testing "cross-block excerpt without offsets"
      (is (m/validate schema/DocumentExcerpt
                      {:id "excerpt-xyz"
                       :text "Gremllm Launch Log\nOur Gremllm"
                       :locator {:document-relative-path "document.md"
                                 :start-block (assoc block :kind :heading :index 1 :start-line 1 :end-line 1
                                                     :block-text-snippet "Gremllm Launch Log")
                                 :end-block block}})))
    (testing "missing :locator fails"
      (is (not (m/validate schema/DocumentExcerpt
                           {:id "e" :text "t"}))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `schema/BlockRef` and `schema/DocumentExcerpt` undefined.

- [ ] **Step 3: Add schemas in `src/gremllm/schema.cljs`**

Insert after `CapturedSelection` / `AnchorContext` (around line 154, just before `StagedSelection`):

```clojure
(def BlockRef
  "Rendered-block identity captured at selection time.
   Advisory only — not exact markdown-source anchoring."
  [:map
   [:kind :keyword]
   [:index :int]
   [:start-line :int]
   [:end-line :int]
   [:block-text-snippet :string]])

(def DocumentExcerpt
  "Durable user-curated document reference. Paired with a user message
   as AI context. Locator is advisory rendered-block metadata.
   Same-block selections may include :start-offset / :end-offset."
  [:map
   [:id :string]
   [:text :string]
   [:locator
    [:map
     [:document-relative-path :string]
     [:start-block BlockRef]
     [:end-block BlockRef]
     [:start-offset {:optional true} :int]
     [:end-offset {:optional true} :int]]]])
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for `block-ref-test` and `document-excerpt-test`.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/schema.cljs test/gremllm/schema_test.cljs
git commit -m "schema: add BlockRef and DocumentExcerpt for S8"
```

---

## Task 2: Schema — Replace `StagedSelection`, extend `Message` with `:context`

**Files:**
- Modify: `src/gremllm/schema.cljs`
- Test: `test/gremllm/schema_test.cljs`

- [ ] **Step 1: Write failing tests**

Append to `test/gremllm/schema_test.cljs`:

```clojure
(deftest message-with-context-test
  (let [excerpt {:id "e1"
                 :text "snippet"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph :index 2
                                         :start-line 3 :end-line 3
                                         :block-text-snippet "Our Gremllm..."}
                           :end-block {:kind :paragraph :index 2
                                       :start-line 3 :end-line 3
                                       :block-text-snippet "Our Gremllm..."}
                           :start-offset 4 :end-offset 11}}]
    (testing "message with excerpt context"
      (is (m/validate schema/Message
                      {:id 1 :type :user :text "reword these"
                       :context {:excerpts [excerpt]}})))
    (testing "message without context still valid"
      (is (m/validate schema/Message
                      {:id 1 :type :user :text "hello"})))))

(deftest persisted-topic-staged-selections-are-document-excerpts-test
  (let [excerpt {:id "e1"
                 :text "snippet"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph :index 2
                                         :start-line 3 :end-line 3
                                         :block-text-snippet "Our..."}
                           :end-block {:kind :paragraph :index 2
                                       :start-line 3 :end-line 3
                                       :block-text-snippet "Our..."}}}]
    (is (m/validate schema/PersistedTopic
                    {:id "t1" :name "T" :session {} :messages []
                     :staged-selections [excerpt]}))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `:context` not declared on `Message`; `StagedSelection` still wraps `CapturedSelection`.

- [ ] **Step 3: Update `src/gremllm/schema.cljs`**

Extend `Message`:

```clojure
(def Message
  [:map
   [:id :int]
   [:type MessageType]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]
   [:context {:optional true}
    [:map
     [:excerpts [:vector DocumentExcerpt]]]]])
```

Delete `StagedSelection` and update `PersistedTopic` to reference `DocumentExcerpt` directly:

```clojure
(def PersistedTopic
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"}        :string]
   [:session {:default {}}              AcpSession]
   [:messages {:default []}             [:vector Message]]
   [:staged-selections {:default []}    [:vector DocumentExcerpt]]])
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS. Pre-existing tests that reference `schema/StagedSelection` must be updated to use `schema/DocumentExcerpt` in this same commit; if `schema_test.cljs` had a fixture for `StagedSelection`, replace it.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/schema.cljs test/gremllm/schema_test.cljs
git commit -m "schema: replace StagedSelection with DocumentExcerpt; extend Message with :context"
```

---

## Task 3: Locator — Update `selection-locator` to emit `DocumentExcerpt.locator` shape

**Files:**
- Modify: `src/gremllm/renderer/ui/document/locator.cljs`
- Create: `test/gremllm/renderer/ui/document/locator_test.cljs`

- [ ] **Step 1: Write failing tests**

Create `test/gremllm/renderer/ui/document/locator_test.cljs`:

```clojure
(ns gremllm.renderer.ui.document.locator-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.ui.document.locator :as locator]))

(def para-block
  {:kind :paragraph :index 2 :start-line 3 :end-line 3
   :text "Our Gremllm launched on a Tuesday."})

(def heading-block
  {:kind :heading :index 1 :start-line 1 :end-line 1
   :text "Gremllm Launch Log"})

(deftest same-block-locator-test
  (testing "same-block selection produces identical start/end BlockRefs with offsets"
    (let [result (locator/selection-locator para-block para-block "launched on a Tuesday")]
      (is (= "document.md" (:document-relative-path result)))
      (is (= {:kind :paragraph :index 2 :start-line 3 :end-line 3
              :block-text-snippet "Our Gremllm launched on a Tuesday."}
             (:start-block result)))
      (is (= (:start-block result) (:end-block result)))
      (is (= 4 (:start-offset result)))
      (is (= 25 (:end-offset result))))))

(deftest cross-block-locator-test
  (testing "cross-block selection omits offsets"
    (let [result (locator/selection-locator heading-block para-block "Gremllm...Our Gremllm")]
      (is (= {:kind :heading :index 1 :start-line 1 :end-line 1
              :block-text-snippet "Gremllm Launch Log"}
             (:start-block result)))
      (is (= {:kind :paragraph :index 2 :start-line 3 :end-line 3
              :block-text-snippet "Our Gremllm launched on a Tuesday."}
             (:end-block result)))
      (is (not (contains? result :start-offset)))
      (is (not (contains? result :end-offset))))))

(deftest same-block-without-findable-offsets-test
  (testing "same-block selection whose text is not found returns locator without offsets"
    (let [result (locator/selection-locator para-block para-block "not-in-block-text")]
      (is (= (:start-block result) (:end-block result)))
      (is (not (contains? result :start-offset)))
      (is (not (contains? result :end-offset))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — current `selection-locator` returns a flat `{:block-kind ...}` shape.

- [ ] **Step 3: Rewrite `selection-locator` in `locator.cljs`**

Replace the existing `selection-locator` (~lines 87–95) with:

```clojure
(defn- ->block-ref [{:keys [kind index start-line end-line text]}]
  {:kind kind
   :index index
   :start-line start-line
   :end-line end-line
   :block-text-snippet (or text "")})

(defn selection-locator
  "Pure transform: build advisory DocumentExcerpt.locator from rendered-block
   records (with :text) and the selected text. Offsets are populated only when
   start and end blocks are the same block and the selected text appears once
   inside its rendered text."
  [start-block end-block selected-text]
  (let [same-block? (and (= (:index start-block) (:index end-block))
                         (= (:kind start-block) (:kind end-block)))
        base {:document-relative-path "document.md"
              :start-block (->block-ref start-block)
              :end-block   (->block-ref end-block)}]
    (if same-block?
      (let [block-text (or (:text start-block) "")
            idx        (.indexOf block-text selected-text)]
        (if (neg? idx)
          base
          (assoc base
                 :start-offset idx
                 :end-offset   (+ idx (count selected-text)))))
      base)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS for locator-test.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/ui/document/locator.cljs test/gremllm/renderer/ui/document/locator_test.cljs
git commit -m "locator: emit DocumentExcerpt.locator shape with start/end BlockRefs"
```

---

## Task 4: Locator — Clean up `selection-locator-from-dom` residue

**Files:**
- Modify: `src/gremllm/renderer/ui/document/locator.cljs`

- [ ] **Step 1: Update `selection-locator-from-dom`**

Drop the DOM-diagnostic fields (`:selection-direction`, `:anchor-node`, `:anchor-offset`, `:focus-node`, `:focus-offset`, `:common-ancestor`) and return the locator map directly. Replace the function body (~lines 109–129) with:

```clojure
(defn selection-locator-from-dom
  "Read rendered-block data-* attrs via the DOM Range and return a locator map
   shaped like DocumentExcerpt.locator. Returns nil when endpoints lack block
   ancestors."
  [_article sel]
  (let [range         (.getRangeAt sel 0)
        start-element (some-> (.-startContainer range) .-parentElement (.closest block-selector))
        end-element   (some-> (.-endContainer range) .-parentElement (.closest block-selector))
        parse-block   (fn [el]
                        (when el
                          {:kind       (some-> (.getAttribute el "data-grem-block-kind") keyword)
                           :index      (js/parseInt (.getAttribute el "data-grem-block-index") 10)
                           :start-line (js/parseInt (.getAttribute el "data-grem-block-start-line") 10)
                           :end-line   (js/parseInt (.getAttribute el "data-grem-block-end-line") 10)
                           :text       (.-textContent el)}))
        start-block   (parse-block start-element)
        end-block     (parse-block end-element)]
    (when (and start-block end-block)
      (selection-locator start-block end-block (.toString sel)))))
```

- [ ] **Step 2: Verify `excerpt-test` capture fixture still matches**

`test/gremllm/renderer/actions/excerpt_test.cljs` defines a `locator-hints` fixture with the old flat shape `{:block-kind :block-index ...}`. Update that fixture to match the new shape from Task 3 so the pre-existing excerpt/capture tests still pass:

```clojure
(def locator-hints
  {:document-relative-path "document.md"
   :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                 :block-text-snippet "Our Gremllm launched on a Tuesday."}
   :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                 :block-text-snippet "Our Gremllm launched on a Tuesday."}
   :start-offset 5
   :end-offset 14})
```

- [ ] **Step 3: Run tests**

Run: `npm run test`
Expected: PASS including pre-existing `excerpt-test/capture-test`.

- [ ] **Step 4: Manual smoke (optional)**

If running `npm run dev`: select text in the document panel, open Dataspex (or browser console) and verify `[:excerpt :locator-hints]` contains only `:document-relative-path`, `:start-block`, `:end-block`, optional offsets. No `:selection-direction` etc.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/ui/document/locator.cljs test/gremllm/renderer/actions/excerpt_test.cljs
git commit -m "locator: drop spike-era DOM diagnostic fields from locator-hints"
```

---

## Task 5: Renderer — pure `capture->excerpt` transform

**Files:**
- Modify: `src/gremllm/renderer/actions/excerpt.cljs`
- Modify: `test/gremllm/renderer/actions/excerpt_test.cljs`

- [ ] **Step 1: Write failing tests**

Append to `test/gremllm/renderer/actions/excerpt_test.cljs`:

```clojure
(deftest capture->excerpt-same-block-test
  (testing "builds DocumentExcerpt from captured text and same-block locator-hints"
    (let [captured {:text "launched on a Tuesday" :range-count 1
                    :anchor-node "#text" :anchor-offset 4
                    :focus-node  "#text" :focus-offset 25
                    :range {}}
          hints    {:document-relative-path "document.md"
                    :start-block {:kind :paragraph :index 2
                                  :start-line 3 :end-line 3
                                  :block-text-snippet "Our Gremllm launched on a Tuesday."}
                    :end-block   {:kind :paragraph :index 2
                                  :start-line 3 :end-line 3
                                  :block-text-snippet "Our Gremllm launched on a Tuesday."}
                    :start-offset 4 :end-offset 25}
          excerpt  (excerpt/capture->excerpt captured hints "abc-123")]
      (is (= "abc-123" (:id excerpt)))
      (is (= "launched on a Tuesday" (:text excerpt)))
      (is (= hints (:locator excerpt))))))

(deftest capture->excerpt-cross-block-test
  (testing "cross-block excerpt has no offsets"
    (let [captured {:text "Gremllm...Our Gremllm"}
          hints    {:document-relative-path "document.md"
                    :start-block {:kind :heading :index 1 :start-line 1 :end-line 1
                                  :block-text-snippet "Gremllm Launch Log"}
                    :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                                  :block-text-snippet "Our Gremllm..."}}
          excerpt  (excerpt/capture->excerpt captured hints "xyz")]
      (is (not (contains? (:locator excerpt) :start-offset))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `capture->excerpt` undefined.

- [ ] **Step 3: Add pure transform in `src/gremllm/renderer/actions/excerpt.cljs`**

Add near the top of the ns (after requires):

```clojure
(defn capture->excerpt
  "Pure transform: ephemeral capture + locator-hints -> durable DocumentExcerpt.
   `id` is supplied by the caller so the impure uuid effect can stay out here."
  [captured locator-hints id]
  {:id      id
   :text    (:text captured)
   :locator locator-hints})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/actions/excerpt.cljs test/gremllm/renderer/actions/excerpt_test.cljs
git commit -m "excerpt: add pure capture->excerpt transform"
```

---

## Task 6: Renderer — `excerpt/stage` builds DocumentExcerpt; staging actions mark-unsaved + auto-save

**Files:**
- Modify: `src/gremllm/renderer/actions/excerpt.cljs`
- Modify: `src/gremllm/renderer/actions/topic.cljs`
- Modify: `test/gremllm/renderer/actions/excerpt_test.cljs`
- Modify: `test/gremllm/renderer/actions/topic_test.cljs`

- [ ] **Step 1: Write failing tests — excerpt stage**

Append to `excerpt_test.cljs`:

```clojure
(deftest stage-builds-document-excerpt-test
  (testing "stage reads captured + locator-hints, dispatches staging.actions/stage with a DocumentExcerpt"
    (let [state {:excerpt {:captured {:text "hello"}
                           :locator-hints {:document-relative-path "document.md"
                                           :start-block {:kind :paragraph :index 2
                                                         :start-line 3 :end-line 3
                                                         :block-text-snippet "hello world"}
                                           :end-block   {:kind :paragraph :index 2
                                                         :start-line 3 :end-line 3
                                                         :block-text-snippet "hello world"}
                                           :start-offset 0 :end-offset 5}}}
          result (excerpt/stage state)
          [[stage-action excerpt] [dismiss-action]] result]
      (is (= :staging.actions/stage stage-action))
      (is (= "hello" (:text excerpt)))
      (is (string? (:id excerpt)))
      (is (= :excerpt.actions/dismiss-popover dismiss-action)))))

(deftest stage-without-capture-is-noop-test
  (is (nil? (excerpt/stage {}))))
```

- [ ] **Step 2: Write failing tests — topic staging actions mark unsaved + auto-save**

Append to `topic_test.cljs` (add requires `excerpt` schema if needed):

```clojure
(def sample-excerpt
  {:id "e1" :text "hello"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "hello world"}
             :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "hello world"}}})

(deftest stage-marks-unsaved-and-auto-saves-test
  (let [state {:workspace {:active-topic-id "t1"}
               :topics    {"t1" {:id "t1" :staged-selections []}}}
        actions (topic/stage state sample-excerpt)]
    (is (= [:effects/save
            (topic-state/staged-selections-path "t1")
            [sample-excerpt]]
           (first actions)))
    (is (= [:topic.actions/mark-active-unsaved] (nth actions 1)))
    (is (= [:topic.effects/auto-save "t1"]      (nth actions 2)))))

(deftest unstage-marks-unsaved-and-auto-saves-test
  (let [state {:workspace {:active-topic-id "t1"}
               :topics    {"t1" {:id "t1" :staged-selections [sample-excerpt]}}}
        actions (topic/unstage state "e1")]
    (is (= [:effects/save
            (topic-state/staged-selections-path "t1") []]
           (first actions)))
    (is (= [:topic.actions/mark-active-unsaved] (nth actions 1)))
    (is (= [:topic.effects/auto-save "t1"]      (nth actions 2)))))

(deftest auto-save-fires-when-staged-selections-present-with-no-messages-test
  (let [state {:workspace {:active-topic-id "t1"}
               :topics    {"t1" {:id "t1"
                                 :messages []
                                 :staged-selections [sample-excerpt]}}}]
    (is (= [[:topic.effects/save-topic "t1"]]
           (topic/auto-save state "t1")))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — current `stage` dispatches with raw capture; staging actions do not mark-unsaved; auto-save bails on empty messages.

- [ ] **Step 4: Update `excerpt/stage`**

Replace in `src/gremllm/renderer/actions/excerpt.cljs`:

```clojure
(defn stage [state]
  (when-let [captured (get-in state excerpt-state/captured-path)]
    (let [hints   (get-in state excerpt-state/locator-hints-path)
          id      (str "excerpt-" (random-uuid))
          excerpt (capture->excerpt captured hints id)]
      [[:staging.actions/stage excerpt]
       [:excerpt.actions/dismiss-popover]])))
```

Note: `random-uuid` is impure but acceptable per existing codebase pattern (see current `topic/stage` using `random-uuid` inline). If this conflicts with FCIS purity for new code, move the uuid generation to an effect in a follow-up; the current commit preserves the existing pattern.

- [ ] **Step 5: Update staging actions in `src/gremllm/renderer/actions/topic.cljs`**

Replace `stage`, `unstage`, `clear-staged`:

```clojure
(defn stage [state excerpt]
  (let [topic-id (topic-state/get-active-topic-id state)
        path     (topic-state/staged-selections-path topic-id)
        existing (or (get-in state path) [])]
    [[:effects/save path (conj existing excerpt)]
     [:topic.actions/mark-active-unsaved]
     [:topic.effects/auto-save topic-id]]))

(defn unstage [state id]
  (let [topic-id (topic-state/get-active-topic-id state)
        path     (topic-state/staged-selections-path topic-id)
        existing (or (get-in state path) [])]
    [[:effects/save path (vec (remove #(= (:id %) id) existing))]
     [:topic.actions/mark-active-unsaved]
     [:topic.effects/auto-save topic-id]]))

(defn clear-staged [state]
  (let [topic-id (topic-state/get-active-topic-id state)
        path     (topic-state/staged-selections-path topic-id)]
    [[:effects/save path []]
     [:topic.actions/mark-active-unsaved]
     [:topic.effects/auto-save topic-id]]))
```

`clear-staged-across-topics` (the document-replace fan-out) stays as-is — replacing document content already marks the document dirty via `document.actions/set-content`, and emitting mark-unsaved per topic there is out of scope.

Relax the `auto-save` guard (same file, existing fn):

```clojure
(defn auto-save
  [state topic-id]
  (let [topic-id          (or topic-id (topic-state/get-active-topic-id state))
        messages          (when topic-id (topic-state/get-topic-field state topic-id :messages))
        staged-selections (when topic-id (topic-state/get-topic-field state topic-id :staged-selections))]
    (when (or (seq messages) (seq staged-selections))
      [[:topic.effects/save-topic topic-id]])))
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS — including the three new tests, all existing topic and excerpt tests (the `excerpt/capture` test fixture from Task 4 still applies).

- [ ] **Step 7: Remove TODO marker**

Delete the S7.3 persistence-gap TODO comment block above `stage` in `topic.cljs` (since this task closes it).

- [ ] **Step 8: Commit**

```bash
git add src/gremllm/renderer/actions/excerpt.cljs src/gremllm/renderer/actions/topic.cljs test/gremllm/renderer/actions/excerpt_test.cljs test/gremllm/renderer/actions/topic_test.cljs
git commit -m "staging: build DocumentExcerpt at stage; close S7.3 persistence gap"
```

---

## Task 7: Renderer — document highlights read new excerpt shape

**Files:**
- Modify: `src/gremllm/renderer/ui/document/highlights.cljs`

- [ ] **Step 1: Update `selection-texts` (and any other callsite using `[:selection :text]`)**

Locate the function (around lines 89–99 per exploration report) and replace `(get-in % [:selection :text])` with `:text`. Concretely, `staged-selections` is now a vector of `DocumentExcerpt`, so:

```clojure
(defn- selection-texts [staged-selections]
  (->> staged-selections
       (map :text)
       (remove str/blank?)
       distinct))
```

(Adapt the exact shape of the fn to what's already there — replace only the accessor.)

- [ ] **Step 2: Manual verification**

Run `npm run dev`. In a workspace with a `document.md`:
- select a paragraph, click Stage
- the pill renders under the composer
- the staged excerpt text stays highlighted in the document panel

If no visible regression and highlight shows correctly, proceed.

- [ ] **Step 3: Commit**

```bash
git add src/gremllm/renderer/ui/document/highlights.cljs
git commit -m "document-highlights: read excerpt text from DocumentExcerpt"
```

---

## Task 8: Renderer — `submit-messages` attaches `:context {:excerpts}`, passes message to send-prompt

**Files:**
- Modify: `src/gremllm/renderer/actions/ui.cljs`
- Modify: `test/gremllm/renderer/actions/ui_test.cljs` (create if missing — follow test patterns in `excerpt_test.cljs`)

- [ ] **Step 1: Write failing tests**

Create or extend `test/gremllm/renderer/actions/ui_test.cljs`:

```clojure
(ns gremllm.renderer.actions.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.ui :as ui]))

(def sample-excerpt
  {:id "e1" :text "hello"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "hello world"}
             :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "hello world"}
             :start-offset 0 :end-offset 5}})

(deftest submit-without-text-is-noop-test
  (let [state {:form {:user-input ""}
               :workspace {:active-topic-id "t1"}
               :topics {"t1" {:staged-selections []}}}]
    (is (nil? (ui/submit-messages state)))))

(deftest submit-without-staged-selections-sends-plain-message-test
  (let [state {:form {:user-input "hello"}
               :workspace {:active-topic-id "t1"}
               :topics {"t1" {:staged-selections []}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ message]          add-msg
        [_ sent-message]     send]
    (is (= :messages.actions/add-to-chat (first add-msg)))
    (is (= :user (:type message)))
    (is (= "hello" (:text message)))
    (is (not (contains? message :context)))
    (is (= :acp.actions/send-prompt (first send)))
    (is (= message sent-message))))

(deftest submit-with-staged-selections-attaches-context-test
  (let [state {:form {:user-input "reword these"}
               :workspace {:active-topic-id "t1"}
               :topics {"t1" {:staged-selections [sample-excerpt]}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ message]          add-msg
        [_ sent-message]     send]
    (is (= "reword these" (:text message)))
    (is (= {:excerpts [sample-excerpt]} (:context message)))
    (is (= message sent-message))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `submit-messages` currently builds a text-only message and sends `text`, not the structured message.

- [ ] **Step 3: Update `submit-messages` in `src/gremllm/renderer/actions/ui.cljs`**

```clojure
(defn submit-messages [state]
  (let [text (form-state/get-user-input state)]
    (when-not (empty? text)
      (let [staged (or (topic-state/get-staged-selections state) [])
            base   {:id   (schema/generate-message-id)
                    :type :user
                    :text text}
            message (if (seq staged)
                      (assoc base :context {:excerpts (vec staged)})
                      base)]
        [[:messages.actions/add-to-chat message]
         [:form.actions/clear-input]
         [:ui.actions/focus-chat-input]
         [:ui.actions/scroll-chat-to-bottom]
         [:acp.actions/send-prompt message]]))))
```

Add `gremllm.renderer.state.topic` to the ns `:require` as `topic-state` if not already present.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/actions/ui.cljs test/gremllm/renderer/actions/ui_test.cljs
git commit -m "submit: attach staged excerpts as message :context and send structured message"
```

---

## Task 9: Renderer — `acp/send-prompt` accepts a message; clears staged on success

**Files:**
- Modify: `src/gremllm/renderer/actions/acp.cljs`
- Modify: `test/gremllm/renderer/actions/acp_test.cljs`

- [ ] **Step 1: Write failing tests**

Append to `acp_test.cljs` (match existing patterns; keep fixture minimal):

```clojure
(deftest send-prompt-with-message-and-staged-clears-on-success-test
  (let [message {:id 1 :type :user :text "reword these"
                 :context {:excerpts [{:id "e1" :text "x"
                                       :locator {:document-relative-path "document.md"
                                                 :start-block {:kind :paragraph :index 2
                                                               :start-line 3 :end-line 3
                                                               :block-text-snippet "x"}
                                                 :end-block   {:kind :paragraph :index 2
                                                               :start-line 3 :end-line 3
                                                               :block-text-snippet "x"}}}]}}
        state   {:workspace {:active-topic-id "t1"}
                 :topics {"t1" {:id "t1" :session {:id "s1"}}}}
        [[_ _ _] promise-effect] (acp/send-prompt state message)
        on-success (get-in promise-effect [1 :on-success])
        on-error   (get-in promise-effect [1 :on-error])]
    (is (some #{[:staging.actions/clear-staged]} on-success)
        "success clears staged selections")
    (is (not (some #{[:staging.actions/clear-staged]} on-error))
        "error preserves staged selections")))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — current `send-prompt` accepts `text` and does not clear staged.

- [ ] **Step 3: Update `send-prompt`**

```clojure
(defn send-prompt [state message]
  (let [topic-id       (topic-state/get-active-topic-id state)
        acp-session-id (topic-state/get-acp-session-id state topic-id)]
    (if acp-session-id
      [[:loading.actions/set-loading? topic-id true]
       [:effects/promise
        {:promise    (.acpPrompt js/window.electronAPI
                                 acp-session-id
                                 (clj->js message))
         :on-success [[:loading.actions/set-loading? topic-id false]
                      [:staging.actions/clear-staged]
                      [:topic.effects/auto-save topic-id]]
         :on-error   [[:loading.actions/set-loading? topic-id false]]}]]
      (js/console.error "[ACP] No session for prompt"))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/actions/acp.cljs test/gremllm/renderer/actions/acp_test.cljs
git commit -m "acp/send-prompt: accept structured message; clear staged on success"
```

---

## Task 10: Preload + IPC — carry structured user message through to main

**Files:**
- Modify: `resources/public/js/preload.js`
- Modify: `src/gremllm/main/core.cljs`
- Modify: `src/gremllm/main/actions.cljs`

No automated test here — this is the IPC wire. Verify with integration / manual.

- [ ] **Step 1: Update preload `acpPrompt`**

In `resources/public/js/preload.js`, the `acpPrompt(sessionId, text)` wrapper becomes `acpPrompt(sessionId, message)`. Forward `message` (a plain JS object) as the second arg to `ipcRenderer.invoke('acp/prompt', sessionId, message)`. No other logic change.

- [ ] **Step 2: Update main IPC handler**

In `src/gremllm/main/core.cljs:118-126` (the `acp/prompt` handler), change the handler signature to receive `message` (JS object) instead of `text`. Convert it to Clojure at the boundary using `(js->clj message :keywordize-keys true)` and pass the map to the send-prompt effect.

Existing TODO at line 119 (`;; TODO: we should pass the document path from Renderer to here`) stays — not in S8 scope.

- [ ] **Step 3: Update `:acp.effects/send-prompt` in `src/gremllm/main/actions.cljs`**

Rename the 3rd parameter from `text` to `message` and pass `message` into `acp-actions/prompt-content-blocks` (Task 11 updates the builder to accept a message):

```clojure
(nxr/register-effect! :acp.effects/send-prompt
  (fn [{:keys [dispatch]} _ acp-session-id message workspace-dir]
    (let [maybe-document-path (some-> workspace-dir io/document-file-path)
          maybe-document-path (when (and maybe-document-path (io/file-exists? maybe-document-path))
                                maybe-document-path)
          content-blocks      (acp-actions/prompt-content-blocks message maybe-document-path)]
      (dispatch [[:ipc.effects/promise->reply
                  (acp-effects/prompt acp-session-id content-blocks)]]))))
```

- [ ] **Step 4: Manual verification**

Run `npm run dev`. With a workspace open and no staged selections, send a plain chat message; confirm ACP still responds. (Task 11 will make excerpts actually show up in the prompt.)

- [ ] **Step 5: Commit**

```bash
git add resources/public/js/preload.js src/gremllm/main/core.cljs src/gremllm/main/actions.cljs
git commit -m "ipc: carry structured user message through acp/prompt boundary"
```

---

## Task 11: Main — `prompt-content-blocks` accepts message with excerpts

**Files:**
- Modify: `src/gremllm/main/actions/acp.cljs`
- Modify: `test/gremllm/main/actions/acp_test.cljs`

- [ ] **Step 1: Rewrite existing tests and add excerpt-aware tests**

Replace and extend `test/gremllm/main/actions/acp_test.cljs`:

```clojure
(deftest text-only-message-test
  (testing "message with no :context produces single text block"
    (is (= [{:type "text" :text "hello"}]
           (acp/prompt-content-blocks {:text "hello"} nil)))))

(deftest text-only-with-document-path-test
  (is (= [{:type "text" :text "hello"}
          {:type "resource_link"
           :uri  "file:///workspace/document.md"
           :name "document.md"}]
         (acp/prompt-content-blocks {:text "hello"} "/workspace/document.md"))))

(deftest same-block-excerpt-includes-text-label-and-offsets-test
  (let [excerpt {:id "e1" :text "launched on a Tuesday"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph :index 2
                                         :start-line 3 :end-line 3
                                         :block-text-snippet "Our Gremllm launched on a Tuesday."}
                           :end-block   {:kind :paragraph :index 2
                                         :start-line 3 :end-line 3
                                         :block-text-snippet "Our Gremllm launched on a Tuesday."}
                           :start-offset 4 :end-offset 25}}
        message {:text "reword these" :context {:excerpts [excerpt]}}
        [text-block] (acp/prompt-content-blocks message nil)
        body (:text text-block)]
    (is (= "text" (:type text-block)))
    (is (re-find #"reword these" body))
    (is (re-find #"References:" body))
    (is (re-find #"launched on a Tuesday" body)
        "full excerpt text appears")
    (is (re-find #"p2" body)
        "compact block label p2 appears")
    (is (re-find #"offset 4-25" body)
        "same-block offsets appear")
    (is (re-find #"Our Gremllm launched on a Tuesday\." body)
        "block text snippet provides disambiguation context")))

(deftest cross-block-excerpt-no-offsets-test
  (let [excerpt {:id "e2" :text "Gremllm Launch Log\nOur Gremllm"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :heading :index 1 :start-line 1 :end-line 1
                                         :block-text-snippet "Gremllm Launch Log"}
                           :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                                         :block-text-snippet "Our Gremllm launched on a Tuesday."}}}
        message {:text "compare these" :context {:excerpts [excerpt]}}
        [text-block] (acp/prompt-content-blocks message nil)
        body (:text text-block)]
    (is (re-find #"h1 -> p2" body))
    (is (not (re-find #"offset" body)))))

(deftest excerpt-with-document-path-appends-resource-link-test
  (let [excerpt {:id "e1" :text "x"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                                         :block-text-snippet "x"}
                           :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                                         :block-text-snippet "x"}}}
        blocks (acp/prompt-content-blocks
                 {:text "t" :context {:excerpts [excerpt]}}
                 "/workspace/document.md")]
    (is (= 2 (count blocks)))
    (is (= "text" (:type (first blocks))))
    (is (= "resource_link" (:type (second blocks))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — current `prompt-content-blocks` accepts `text`, not a message map.

- [ ] **Step 3: Rewrite `src/gremllm/main/actions/acp.cljs`**

```clojure
(ns gremllm.main.actions.acp
  (:require [clojure.string :as str]
            [gremllm.main.io :as io]))

(defn- block-label
  "Compact label for a BlockRef like p2, li4, h1, code5."
  [{:keys [kind index]}]
  (let [prefix (case kind
                 :heading     "h"
                 :paragraph   "p"
                 :list-item   "li"
                 :code-block  "code"
                 :blockquote  "bq"
                 :table       "tbl"
                 (name kind))]
    (str prefix index)))

(defn- locator-label [{:keys [start-block end-block start-offset end-offset]}]
  (let [start (block-label start-block)
        end   (block-label end-block)
        base  (if (= start end) start (str start " -> " end))]
    (if (and start-offset end-offset (= start end))
      (str base " offset " start-offset "-" end-offset)
      base)))

(defn- render-excerpt [idx {:keys [text locator]}]
  (let [{:keys [start-block]} locator]
    (str "  [" (inc idx) "] " (locator-label locator) "\n"
         "      text: " (pr-str text) "\n"
         "      block context: " (pr-str (:block-text-snippet start-block)))))

(defn- render-references [excerpts]
  (str "\nReferences:\n"
       (->> excerpts
            (map-indexed render-excerpt)
            (str/join "\n"))))

(defn prompt-content-blocks
  "Build ACP prompt content blocks from a structured user message and optional
   document path. The message carries :text (user instruction) and optional
   :context {:excerpts [DocumentExcerpt]}. Excerpts are rendered as a References
   section appended to the user text in a single text block, followed by the
   resource_link for document.md when present."
  [message document-path]
  (let [{:keys [text context]} message
        excerpts (:excerpts context)
        body     (if (seq excerpts)
                   (str text (render-references excerpts))
                   text)]
    (cond-> [{:type "text" :text body}]
      document-path (conj {:type "resource_link"
                           :uri  (io/path->file-uri document-path)
                           :name (io/path-basename document-path)}))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS — both existing refactored cases and new excerpt cases.

- [ ] **Step 5: Manual verification**

Run `npm run dev`, open a workspace with `document.md`, select some text, stage, type `reword these`, send. In the ACP client log (or Dataspex trace of the dispatched effect payload) confirm the prompt's text block contains `reword these`, the `References:` block, the excerpt text, a compact label like `p2 offset 4-25`, and the `block context` line.

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/main/actions/acp.cljs test/gremllm/main/actions/acp_test.cljs
git commit -m "acp-prompt: build References section from structured user message"
```

---

## Task 12: UI — Compact References row on user message bubble; pills read new shape

**Files:**
- Modify: `src/gremllm/renderer/ui/chat.cljs`
- Modify: `test/gremllm/renderer/ui/chat_test.cljs` (create if missing — otherwise extend)

- [ ] **Step 1: Write failing tests**

Create or extend `test/gremllm/renderer/ui/chat_test.cljs`. Tests assert on the returned hiccup data structure (Replicant components are pure data):

```clojure
(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [gremllm.renderer.ui.chat :as chat]))

(defn- flatten-strings [hiccup]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [x]
        (when (string? x) (swap! acc conj x))
        x)
      hiccup)
    @acc))

(defn- contains-text? [hiccup s]
  (some #(str/includes? % s) (flatten-strings hiccup)))

(def same-block-excerpt
  {:id "e1" :text "this is a selection longer than forty characters abc"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph :index 3 :start-line 5 :end-line 5
                           :block-text-snippet "full block text"}
             :end-block   {:kind :paragraph :index 3 :start-line 5 :end-line 5
                           :block-text-snippet "full block text"}
             :start-offset 0 :end-offset 52}})

(def cross-block-excerpt
  {:id "e2" :text "spanning"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :heading :index 1 :start-line 1 :end-line 1
                           :block-text-snippet "Title"}
             :end-block   {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "Body"}}})

(deftest plain-user-message-has-no-references-test
  (let [hiccup (chat/render-message {:id 1 :type :user :text "hello"})]
    (is (not (contains-text? hiccup "References")))
    (is (contains-text? hiccup "hello"))))

(deftest user-message-with-same-block-excerpt-renders-compact-pill-test
  (let [hiccup (chat/render-message
                 {:id 1 :type :user :text "reword these"
                  :context {:excerpts [same-block-excerpt]}})]
    (is (contains-text? hiccup "reword these"))
    (is (contains-text? hiccup "p3")
        "label p3 appears")
    (is (contains-text? hiccup "this is a selection longer than forty c")
        "truncated excerpt text appears")
    (is (not (contains-text? hiccup "full block text"))
        "block-text-snippet is NOT rendered in the bubble")
    (is (not (contains-text? hiccup "this is a selection longer than forty characters abc"))
        "full excerpt body is NOT rendered in the bubble (truncated at 40)")))

(deftest user-message-with-cross-block-excerpt-renders-arrow-label-test
  (let [hiccup (chat/render-message
                 {:id 1 :type :user :text "compare"
                  :context {:excerpts [cross-block-excerpt]}})]
    (is (contains-text? hiccup "h1 -> p2"))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test`
Expected: FAIL — `render-user-message` currently renders only `[:span (:text message)]`.

- [ ] **Step 3: Update `src/gremllm/renderer/ui/chat.cljs`**

Add pill helpers and extend `render-user-message`:

```clojure
(defn- excerpt-block-label
  "Short advisory locator label like p3 or h1 -> p2."
  [{{:keys [start-block end-block]} :locator}]
  (let [prefix (fn [{:keys [kind]}]
                 (case kind
                   :heading "h"
                   :paragraph "p"
                   :list-item "li"
                   :code-block "code"
                   :blockquote "bq"
                   :table "tbl"
                   (name kind)))
        start (str (prefix start-block) (:index start-block))
        end   (str (prefix end-block)   (:index end-block))]
    (if (= start end) start (str start " -> " end))))

(def ^:private excerpt-snippet-cap 40)

(defn- truncate [s n]
  (if (> (count s) n) (subs s 0 n) s))

(defn- render-excerpt-pill [excerpt]
  [:span.excerpt-pill
   [:span.excerpt-pill__label (excerpt-block-label excerpt)]
   [:span.excerpt-pill__text (truncate (:text excerpt) excerpt-snippet-cap)]])

(defn- render-references [excerpts]
  [:div.message-references
   [:span.message-references__label "References:"]
   (into [:span.message-references__pills]
         (map render-excerpt-pill excerpts))])

(defn- render-user-message [{:keys [text context]}]
  [e/user-message
   [:span text]
   (when-let [excerpts (seq (:excerpts context))]
     (render-references excerpts))])
```

Also update `render-staged-selections` (at `chat.cljs:55-68` per exploration) to read `:text` directly from the new excerpt shape (no longer `[:selection :text]`). Adapt only the accessor.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm run test`
Expected: PASS.

- [ ] **Step 5: Manual verification**

Run `npm run dev`. In a workspace with `document.md`:
- select a same-block paragraph, stage, send "reword these"
- user bubble shows "reword these" + `References: p<N> <truncated excerpt...>`
- stage text longer than 40 chars: pill text is truncated
- stage a cross-block selection, send: pill shows `h1 -> p2` style label
- no quote-wall of excerpt text appears in the bubble

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/ui/chat.cljs test/gremllm/renderer/ui/chat_test.cljs
git commit -m "chat: render compact References row on user messages with excerpts"
```

---

## End-to-End Verification

After all tasks, manually verify:

1. `npm run test` — all unit tests pass.
2. `npm run test:all` — integration tests still pass (none changed; ACP IPC signature change verified by hand).
3. `npm run dev`, open a workspace with `document.md`:
   - **Same-block send:** select a paragraph, Stage, type "reword this", Send.
     - Transcript shows compact pill `p<N> ...`.
     - Pending diff / ACP reply references the selected text (advisory only; no exact anchoring guarantee).
     - Staged pill clears after reply succeeds.
   - **Cross-block send:** select across blocks, Stage, Send.
     - Pill shows `h<X> -> p<Y>` label.
   - **Send failure preservation:** disable network or stop the agent mid-send so the ACP prompt rejects.
     - Staged pill remains after the failure.
   - **Document replace invalidation:** ask the agent to replace the document; staged selections across all topics clear, popover dismisses.
   - **Persistence:** Stage a selection, close the app, reopen. The staged pill is still there (S7.3 gap closed).
4. Dataspex trace:
   - `[:excerpt :locator-hints]` after selection contains `:document-relative-path`, `:start-block`, `:end-block`, and (same-block only) `:start-offset`/`:end-offset`. No `:selection-direction` etc.
   - `[:topics <id> :staged-selections]` is a vector of `DocumentExcerpt` maps (no `:selection` wrapper).
   - User messages in `[:topics <id> :messages]` after send include `:context {:excerpts [...]}` when sent with staged selections.

---

## Non-Goals (from spec, not in this plan)

- Exact markdown source anchoring.
- Highlight restoration or range recovery from locator data.
- Tracked-change application from locators.
- Content-hash / document-version invalidation (blanket clear-all on `set-content` is S8's contract).
- Broader ACP send-error UX.
- Specialized agent / quick-action topic creation (S9).
