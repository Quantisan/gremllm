# WebSearch In-Progress Indicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display a minimalist `<details>` bubble in the chat thread while a WebSearch runs, persisting as a completed record ("Searched the web — <query>") after the search finishes.

**Architecture:** Extend the `Message` schema with a new `:tool-search` type and optional tracking fields (`tool-call-id`, `status`, `query`). The `:tool-call` ACP event appends a pending message; `:tool-call-update` events mutate it in place via path-based `[:effects/save ...]`. Style mirrors `.reasoning-bubble` with a `<details>` element open while pending, collapsed when complete.

**Tech Stack:** ClojureScript, Replicant (hiccup UI), Nexus (state + effects), Malli (schemas), PicoCSS

---

## File Map

| File | Change |
|---|---|
| `src/gremllm/schema.cljs` | Add `:tool-search` to `MessageType`; add optional fields to `Message` |
| `src/gremllm/schema/codec/acp.cljs` | Declare `:title`, `:status`, `:raw-input` on `AcpToolCall` + `AcpToolCallUpdate` |
| `src/gremllm/renderer/state/topic.cljs` | Add `find-message-index-by-tool-call-id` helper |
| `src/gremllm/renderer/actions/topic.cljs` | Add `upsert-tool-search` action |
| `src/gremllm/renderer/actions/acp.cljs` | Extend `handle-tool-event` with two WebSearch branches |
| `src/gremllm/renderer/actions.cljs` | Register `:topic.actions/upsert-tool-search` |
| `src/gremllm/renderer/ui/elements.cljs` | Add `tool-search-message` defalias |
| `src/gremllm/renderer/ui/chat.cljs` | Add `render-tool-search-message` + `:tool-search` case |
| `src/gremllm/renderer/ui.cljs` | Fix `awaiting-response?` predicate (spinner overlap) |
| `resources/public/index.html` | Add `.tool-search-bubble` CSS rule |

---

## Task 1: Extend Message schema

**Files:**
- Modify: `src/gremllm/schema.cljs:10-12,102-110`
- Test: `test/gremllm/schema_test.cljs`

- [ ] **Step 1: Write failing schema test**

Add to `test/gremllm/schema_test.cljs` after the existing `create-message` fn:

```clojure
(deftest tool-search-message-validates
  (testing "accepts all optional fields"
    (let [msg {:id 1 :type :tool-search :text ""
               :tool-call-id "toolu_abc" :status "pending" :query nil}]
      (is (m/validate schema/Message msg))))
  (testing "accepts completed state"
    (let [msg {:id 2 :type :tool-search :text "CRDT vs OT"
               :tool-call-id "toolu_abc" :status "completed" :query "CRDT vs OT"}]
      (is (m/validate schema/Message msg))))
  (testing "rejects unknown message types"
    (is (not (m/validate schema/Message {:id 3 :type :unknown-type :text ""})))))
```

- [ ] **Step 2: Run tests — expect failures for :tool-search cases**

```bash
npm run test:ci
```

Expected: `tool-search-message-validates` fails with `:tool-search` not in MessageType enum.

- [ ] **Step 3: Extend MessageType and Message**

In `src/gremllm/schema.cljs`:

Line 10-12, change:
```clojure
(def MessageType
  "Valid message type identifiers."
  [:enum :user :assistant :reasoning :tool-use])
```
to:
```clojure
(def MessageType
  "Valid message type identifiers."
  [:enum :user :assistant :reasoning :tool-use :tool-search])
```

Lines 102-110, change:
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
to:
```clojure
(def Message
  [:map
   [:id :int]
   [:type MessageType]
   [:text :string]
   [:tool-call-id {:optional true} :string]
   [:status        {:optional true} :string]
   [:query         {:optional true} :string]
   [:attachments {:optional true} [:vector AttachmentRef]]
   [:context {:optional true}
    [:map
     [:excerpts [:vector DocumentExcerpt]]]]])
```

- [ ] **Step 4: Run tests — all pass**

```bash
npm run test:ci
```

Expected: all tests pass (existing Message tests still pass because new fields are optional).

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/schema.cljs test/gremllm/schema_test.cljs
git commit -m "feat(schema): add :tool-search MessageType with optional tool-call-id/status/query fields"
```

---

## Task 2: Extend ACP codec to declare consumed fields

**Files:**
- Modify: `src/gremllm/schema/codec/acp.cljs:145-163`
- Test: `test/gremllm/schema/codec/acp_test.cljs`

Context: per the design rule at `acp.cljs:9-15`, schemas track the consumer contract — fields must be declared if a consumer reads them. Our new handler will read `:title`, `:status`, and `:raw-input.query` from both `AcpToolCall` and `AcpToolCallUpdate`.

- [ ] **Step 1: Write failing codec test**

Add to `test/gremllm/schema/codec/acp_test.cljs`, in Section 1 (after `acp-coerces-agent-message-chunk`):

```clojure
(deftest acp-coerces-websearch-tool-call
  (testing "title, status, and raw-input survive coercion on :tool-call"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "tool_call"
                                       :toolCallId    "toolu_abc"
                                       :title         "Web search"
                                       :status        "pending"
                                       :rawInput      {}
                                       :meta          {:claudeCode {:toolName "WebSearch"}}}))]
      (is (= "pending" (get-in result [:update :status])))
      (is (= "Web search" (get-in result [:update :title])))
      (is (= {} (get-in result [:update :raw-input])))))

  (testing "raw-input.query survives coercion on :tool-call-update"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "tool_call_update"
                                       :toolCallId    "toolu_abc"
                                       :title         "\"CRDT vs OT\""
                                       :rawInput      {:query "CRDT vs OT"}
                                       :meta          {:claudeCode {:toolName "WebSearch"}}}))]
      (is (= "CRDT vs OT" (get-in result [:update :raw-input :query])))
      (is (= "\"CRDT vs OT\"" (get-in result [:update :title]))))))
```

- [ ] **Step 2: Run tests — expect failures**

```bash
npm run test:ci
```

Expected: the two new `is` assertions fail (fields missing from coerced result or assertion fails).

- [ ] **Step 3: Declare fields on AcpToolCall and AcpToolCallUpdate**

In `src/gremllm/schema/codec/acp.cljs`:

Change `AcpToolCall` (lines 145-151):
```clojure
(def AcpToolCall
  "Pre-execution tool call notification.
   Side-channel: remember-tool-name reads :tool-call-id + :meta.:claude-code.:tool-name."
  [:map
   [:session-update [:= :tool-call]]
   [:tool-call-id :string]
   [:title     {:optional true} :string]
   [:status    {:optional true} :string]
   [:raw-input {:optional true} [:map [:query {:optional true} :string]]]
   [:meta {:optional true} AcpToolMeta]])
```

Change `AcpToolCallUpdate` (lines 153-163):
```clojure
(def AcpToolCallUpdate
  "Post-execution / streaming refinement update.
   Consumers: handle-tool-event, tool-response-diffs, acp-read-display-label,
   tool-response-read-event?, tool-response-read-with-file-metadata?.
   :kind absence (vs. presence) gates streaming-refinement filtering."
  [:map
   [:session-update [:= :tool-call-update]]
   [:tool-call-id :string]
   [:title     {:optional true} :string]
   [:status    {:optional true} :string]
   [:raw-input {:optional true} [:map [:query {:optional true} :string]]]
   [:kind    {:optional true} [:maybe AcpToolKind]]
   [:meta    {:optional true} AcpToolMeta]
   [:content {:optional true} [:maybe [:vector AcpToolCallContentItem]]]])
```

- [ ] **Step 4: Run tests — all pass**

```bash
npm run test:ci
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/schema/codec/acp.cljs test/gremllm/schema/codec/acp_test.cljs
git commit -m "feat(acp-codec): declare title/status/raw-input on AcpToolCall and AcpToolCallUpdate"
```

---

## Task 3: Add state helper — find message by tool-call-id

**Files:**
- Modify: `src/gremllm/renderer/state/topic.cljs`
- Test: `test/gremllm/renderer/actions/acp_test.cljs` (reuse existing test ns)

- [ ] **Step 1: Write failing test**

Add to `test/gremllm/renderer/actions/acp_test.cljs` (after existing `deftest`s):

```clojure
(deftest test-find-message-index-by-tool-call-id
  (let [state {:topics {"t1" {:messages [{:type :user :text "q"}
                                          {:type :tool-search :tool-call-id "toolu_1"
                                           :text "" :status "pending"}
                                          {:type :assistant :text "a"}]}}
               :active-topic-id "t1"}]
    (testing "finds index of message with matching tool-call-id"
      (is (= 1 (topic-state/find-message-index-by-tool-call-id state "toolu_1"))))
    (testing "returns nil when no message has matching tool-call-id"
      (is (nil? (topic-state/find-message-index-by-tool-call-id state "toolu_missing"))))))
```

Note: `topic-state` is already required in this test ns at line 1 — but the test file currently requires `gremllm.schema.codec.acp`. Check that `topic-state` is required; add it if not:

```clojure
(:require ...
          [gremllm.renderer.state.topic :as topic-state])
```

- [ ] **Step 2: Run tests — expect failure**

```bash
npm run test:ci
```

Expected: `find-message-index-by-tool-call-id` not found.

- [ ] **Step 3: Implement the helper**

Add to the end of `src/gremllm/renderer/state/topic.cljs`:

```clojure
(defn find-message-index-by-tool-call-id
  "Returns the index of the first message in the active topic with a matching
   :tool-call-id, or nil if no match."
  [state tool-call-id]
  (some (fn [[idx msg]]
          (when (= tool-call-id (:tool-call-id msg))
            idx))
        (map-indexed vector (get-messages state))))
```

- [ ] **Step 4: Run tests — all pass**

```bash
npm run test:ci
```

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/state/topic.cljs test/gremllm/renderer/actions/acp_test.cljs
git commit -m "feat(state/topic): add find-message-index-by-tool-call-id helper"
```

---

## Task 4: Add upsert-tool-search action

**Files:**
- Modify: `src/gremllm/renderer/actions/topic.cljs`
- Modify: `src/gremllm/renderer/actions.cljs`
- Test: `test/gremllm/renderer/actions/acp_test.cljs`

- [ ] **Step 1: Write failing tests**

Add to `test/gremllm/renderer/actions/acp_test.cljs`. This requires importing the topic action namespace — add to the `ns` require block:

```clojure
[gremllm.renderer.actions.topic :as topic]
```

Then add the tests:

```clojure
(deftest test-upsert-tool-search
  (let [state {:topics {"t1" {:messages [{:type :user :text "q"}
                                          {:type :tool-search
                                           :tool-call-id "toolu_1"
                                           :status "pending"
                                           :query nil
                                           :text ""}]}}
               :active-topic-id "t1"}]

    (testing "emits path-based saves for each field in patch"
      (let [effects (topic/upsert-tool-search state "toolu_1" {:status "completed"
                                                                :query  "CRDT vs OT"
                                                                :text   "CRDT vs OT"})]
        (is (= #{[:effects/save [:topics "t1" :messages 1 :status] "completed"]
                 [:effects/save [:topics "t1" :messages 1 :query]  "CRDT vs OT"]
                 [:effects/save [:topics "t1" :messages 1 :text]   "CRDT vs OT"]}
               (set effects)))))

    (testing "returns nil when tool-call-id has no matching message"
      (is (nil? (topic/upsert-tool-search state "toolu_missing" {:status "completed"}))))))
```

- [ ] **Step 2: Run tests — expect failure**

```bash
npm run test:ci
```

Expected: `upsert-tool-search` not found.

- [ ] **Step 3: Implement upsert-tool-search in actions/topic.cljs**

Add after `append-pending-diffs` (around line 66) in `src/gremllm/renderer/actions/topic.cljs`:

```clojure
(defn upsert-tool-search
  "Merges patch fields into the :tool-search message identified by tool-call-id.
   Uses path-based [:effects/save ...] for each field. Returns nil if no match."
  [state tool-call-id patch]
  (if-let [idx (topic-state/find-message-index-by-tool-call-id state tool-call-id)]
    (let [topic-id   (topic-state/get-active-topic-id state)
          msg-path   (conj (topic-state/topic-field-path topic-id :messages) idx)]
      (reduce-kv
        (fn [effects field val]
          (conj effects [:effects/save (conj msg-path field) val]))
        []
        patch))
    (do
      (js/console.warn "[ACP] upsert-tool-search: no message for tool-call-id" tool-call-id)
      nil)))
```

- [ ] **Step 4: Register in actions.cljs**

In `src/gremllm/renderer/actions.cljs`, add after `:topic.actions/append-pending-diffs` (line 175):

```clojure
(nxr/register-action! :topic.actions/upsert-tool-search topic/upsert-tool-search)
```

- [ ] **Step 5: Run tests — all pass**

```bash
npm run test:ci
```

- [ ] **Step 6: Commit**

```bash
git add src/gremllm/renderer/actions/topic.cljs src/gremllm/renderer/actions.cljs test/gremllm/renderer/actions/acp_test.cljs
git commit -m "feat(actions/topic): add upsert-tool-search for in-place tool-search message mutation"
```

---

## Task 5: Extend handle-tool-event for WebSearch

**Files:**
- Modify: `src/gremllm/renderer/actions/acp.cljs:40-54`
- Test: `test/gremllm/renderer/actions/acp_test.cljs`

- [ ] **Step 1: Write failing tests**

Add to `test/gremllm/renderer/actions/acp_test.cljs` inside `test-handle-tool-event`:

```clojure
  (testing "appends :tool-search message for WebSearch :tool-call"
    (let [state   {:topics {"t1" {:messages []}} :active-topic-id "t1"}
          effects (acp/handle-tool-event
                    state
                    {:session-update :tool-call
                     :tool-call-id   "toolu_ws"
                     :status         "pending"
                     :title          "Web search"
                     :raw-input      {}
                     :meta           {:claude-code {:tool-name "WebSearch"}}}
                    999)]
      (is (= [[:messages.actions/add-to-chat-no-save "t1"
               {:id 999 :type :tool-search :tool-call-id "toolu_ws"
                :status "pending" :query nil :text ""}]]
             effects))))

  (testing "dispatches upsert-tool-search for WebSearch :tool-call-update with query"
    (let [state   {:topics {"t1" {:messages [{:type         :tool-search
                                               :tool-call-id "toolu_ws"
                                               :status       "pending"
                                               :query        nil
                                               :text         ""}]}}
                   :active-topic-id "t1"}
          effects (acp/handle-tool-event
                    state
                    {:session-update :tool-call-update
                     :tool-call-id   "toolu_ws"
                     :raw-input      {:query "CRDT vs OT"}
                     :title          "\"CRDT vs OT\""
                     :meta           {:claude-code {:tool-name "WebSearch"}}}
                    999)]
      (is (= [[:topic.actions/upsert-tool-search "toolu_ws"
               {:query "CRDT vs OT" :text "CRDT vs OT"}]]
             effects))))

  (testing "dispatches upsert-tool-search with status on WebSearch completion"
    (let [state   {:topics {"t1" {:messages [{:type         :tool-search
                                               :tool-call-id "toolu_ws"
                                               :status       "pending"
                                               :query        "CRDT vs OT"
                                               :text         "CRDT vs OT"}]}}
                   :active-topic-id "t1"}
          effects (acp/handle-tool-event
                    state
                    {:session-update :tool-call-update
                     :tool-call-id   "toolu_ws"
                     :status         "completed"
                     :meta           {:claude-code {:tool-name "WebSearch"}}}
                    999)]
      (is (= [[:topic.actions/upsert-tool-search "toolu_ws" {:status "completed"}]]
             effects))))
```

- [ ] **Step 2: Run tests — expect failures**

```bash
npm run test:ci
```

Expected: 3 new failing tests.

- [ ] **Step 3: Extend handle-tool-event**

Replace `handle-tool-event` in `src/gremllm/renderer/actions/acp.cljs` (lines 40-54):

```clojure
(defn- websearch? [update]
  (= "WebSearch" (get-in update [:meta :claude-code :tool-name])))

(defn handle-tool-event
  "Handles ACP tool-related session updates.
   Returns chat effects for displayable tool calls and reads,
   or diff effects for tool-call-updates with diffs."
  [state update message-id]
  (cond
    (and (websearch? update) (= :tool-call (:session-update update)))
    (let [topic-id (topic-state/get-active-topic-id state)
          msg      {:id           message-id
                    :type         :tool-search
                    :tool-call-id (:tool-call-id update)
                    :status       (or (:status update) "pending")
                    :query        nil
                    :text         ""}]
      (when-not (m/validate schema/Message msg)
        (throw (js/Error. (str "Invalid :tool-search Message: "
                               (pr-str (m/explain schema/Message msg))))))
      [[:messages.actions/add-to-chat-no-save topic-id msg]])

    (and (websearch? update) (= :tool-call-update (:session-update update)))
    (let [new-query (get-in update [:raw-input :query])
          patch     (cond-> {}
                      (:status update) (assoc :status (:status update))
                      new-query        (assoc :query new-query :text new-query))]
      (when (seq patch)
        [[:topic.actions/upsert-tool-search (:tool-call-id update) patch]]))

    (and (acp-codec/tool-response-read-event? update)
         (acp-codec/tool-response-read-with-file-metadata? update))
    (start-response (topic-state/get-active-topic-id state)
                    :tool-use
                    (acp-codec/acp-read-display-label update)
                    message-id)

    (acp-codec/tool-response-has-diffs? update)
    [[:topic.actions/append-pending-diffs (acp-codec/tool-response-diffs update)]]))
```

- [ ] **Step 4: Run tests — all pass**

```bash
npm run test:ci
```

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/actions/acp.cljs test/gremllm/renderer/actions/acp_test.cljs
git commit -m "feat(actions/acp): handle WebSearch :tool-call and :tool-call-update events"
```

---

## Task 6: Add UI element and chat render dispatch

**Files:**
- Modify: `src/gremllm/renderer/ui/elements.cljs` (after line 35)
- Modify: `src/gremllm/renderer/ui/chat.cljs`

No unit tests — verify visually in Task 9.

- [ ] **Step 1: Add tool-search-message defalias to elements.cljs**

Add after the `tool-use-message` defalias (after line 35) in `src/gremllm/renderer/ui/elements.cljs`:

```clojure
(defalias tool-search-message [{:keys [completed? summary query]} & _body]
  [:article.tool-search-bubble
   [:details {:open (not completed?)}
    [:summary.tool-search-summary summary]
    (when query [:div.tool-search-body query])]])
```

- [ ] **Step 2: Add render-tool-search-message to chat.cljs**

Add after `render-tool-use-message` (after line 34) in `src/gremllm/renderer/ui/chat.cljs`:

```clojure
(defn- render-tool-search-message [{:keys [status query]}]
  (let [completed? (= "completed" status)
        label      (if completed? "Searched the web" "Searching the web")
        summary    (if query (str label " — " (truncate query 60)) label)]
    [e/tool-search-message {:completed? completed? :summary summary :query query}]))
```

- [ ] **Step 3: Add :tool-search case to render-message dispatch**

In `src/gremllm/renderer/ui/chat.cljs`, change `render-message` (lines 62-69):

```clojure
(defn- render-message [message]
  (case (:type message)
    :user        (render-user-message message)
    :assistant   (render-assistant-message message)
    :reasoning   (render-reasoning-message message)
    :tool-use    (render-tool-use-message message)
    :tool-search (render-tool-search-message message)
    [:div "Unknown message type:" (:type message)]))
```

- [ ] **Step 4: Run tests — all pass**

```bash
npm run test:ci
```

- [ ] **Step 5: Commit**

```bash
git add src/gremllm/renderer/ui/elements.cljs src/gremllm/renderer/ui/chat.cljs
git commit -m "feat(ui): add tool-search-message element and render-tool-search-message"
```

---

## Task 7: Fix awaiting-response? spinner overlap

**Files:**
- Modify: `src/gremllm/renderer/ui.cljs:65-66`

The current check `(not= :assistant ...)` shows the "Computing..." spinner for any tail that isn't `:assistant` — including `:tool-search` and `:reasoning`. With a `:tool-search` bubble showing, both indicators appear simultaneously. The fix: show the spinner only when the last message is `:user` (nothing has come back yet).

- [ ] **Step 1: Update awaiting-response? predicate**

In `src/gremllm/renderer/ui.cljs`, change lines 65-66:

Old:
```clojure
awaiting-response? (and (loading-state/loading? state active-topic-id)
                        (not= :assistant (:type (peek messages))))]
```

New:
```clojure
awaiting-response? (and (loading-state/loading? state active-topic-id)
                        (= :user (:type (peek messages))))]
```

- [ ] **Step 2: Run tests — all pass**

```bash
npm run test:ci
```

- [ ] **Step 3: Commit**

```bash
git add src/gremllm/renderer/ui.cljs
git commit -m "fix(ui): show computing spinner only when last message is :user, preventing double indicator"
```

---

## Task 8: Add CSS for .tool-search-bubble

**Files:**
- Modify: `resources/public/index.html` (near line 123)

- [ ] **Step 1: Add .tool-search-bubble rule**

In `resources/public/index.html`, add after the `.reasoning-bubble` rule (after line 127):

```css
        .tool-search-bubble {
            background: rgba(232, 220, 196, 0.05);
            border-left: 3px solid var(--tva-teal);
            opacity: 0.85;
            padding: 0.5rem 0.75rem;
            margin: 0.5rem 0;
            font-size: 0.9rem;
        }

        .tool-search-summary {
            cursor: pointer;
            color: var(--pico-muted-color);
        }

        .tool-search-body {
            margin-top: 0.25rem;
            font-size: 0.85rem;
            opacity: 0.8;
        }
```

- [ ] **Step 2: Commit**

```bash
git add resources/public/index.html
git commit -m "feat(css): add .tool-search-bubble styles"
```

---

## Task 9: Integration test — manual verification

- [ ] **Step 1: Start the dev server**

```bash
npm run dev
```

Wait for the Electron window to open and the app to load.

- [ ] **Step 2: Open a topic and trigger a WebSearch**

In the chat input, enter a prompt like:

> Search the web for the current state of CRDT vs OT for real-time collaboration

Send it.

- [ ] **Step 3: Verify in-progress state**

While the search is running, you should see:
- A `▼ Searching the web` bubble (no query yet — query is nil at first `:tool-call`)
- After a moment, the summary updates to `▼ Searching the web — CRDT vs OT real-time...`
- The bubble body shows the full query when expanded
- **No** duplicate "Computing..." spinner below the bubble

- [ ] **Step 4: Verify completed state**

After the search finishes and the assistant response arrives:
- The bubble summary reads `▶ Searched the web — CRDT vs OT real-time...` (collapsed)
- The overall assistant response follows below

- [ ] **Step 5: Verify persistence**

Save the topic (Cmd+S or via menu), close the app, reopen, and navigate to the topic. The completed `▶ Searched the web — ...` bubble should still be there.

- [ ] **Step 6: Run full test suite**

```bash
npm run test:all
```

Expected: all tests pass.
