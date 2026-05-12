(ns gremllm.schema.codec.acp-test
  (:require [cljs.test :refer [are deftest is testing]]
            [gremllm.schema.codec.acp :as acp-codec]))

;; ============================================================================
;; ACP Trust Boundary — Test Design Language
;; ============================================================================
;;
;; Two test species, two sections.
;;
;; SECTION 1 — Boundary coercion (this section): one deftest per SDK message
;; type (agent-message-chunk, tool-call, tool-call-update, permission-request,
;; etc.). Each contains one canonical happy-path clause plus named clauses for
;; the invariants this boundary actively enforces.
;;
;; SECTION 2 — Consumer helpers: one deftest per pure helper that operates on
;; already-coerced data (acp-update-text, edit-diffs, ...).
;;
;; Rules
;;   - A testing-clause name states the invariant being protected
;;     ("throws without :kind"), not the input shape ("with diffs").
;;   - Build JS payloads via (session-update-js ...) / (permission-request-js
;;     ...). Test bodies show only the fields that matter for the case.
;;   - Trivial keyword-lookup predicates aren't tested — they fail loudly if
;;     shapes drift.
;;   - Don't add a clause unless it proves a distinct invariant. No
;;     fixture-variant tests.
;;
;; If you can't name your testing clause after an invariant, you're probably
;; testing the fixture, not the boundary.
;; ============================================================================

(def ^:private test-acp-session-id "e0eb7ced-4b3f-45af-b911-6b9de025788b")

(defn- session-update-js
  "Build a JS-shaped session update payload as the ACP bridge would deliver it.
   `update-fields` is a CLJS map with the SDK's camelCase keyword keys
   (e.g. :sessionUpdate, :toolCallId); clj->js preserves keyword names
   verbatim, so the resulting object matches the SDK wire shape."
  [update-fields]
  (clj->js {:sessionId test-acp-session-id :update update-fields}))

(defn- permission-request-js
  "Build a JS-shaped requestPermission payload.
   Default `options` provide a single reject_once entry; tests override only
   when the option shape itself is under test."
  [tool-call-fields & [option-fields]]
  (clj->js {:sessionId test-acp-session-id
            :toolCall  tool-call-fields
            :options   (or option-fields
                           [{:optionId "reject" :name "Reject" :kind "reject_once"}])}))

;; ========================================
;; Section 1: Boundary Coercion
;; ========================================

(deftest acp-coerces-agent-message-chunk
  (testing "happy path: chunk text accessible at :content :text"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "agent_message_chunk"
                                       :content {:text "Hello" :type "text"}}))]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= :agent-message-chunk (get-in result [:update :session-update])))
      (is (= "Hello" (get-in result [:update :content :text])))))

  (testing "tolerates content without :type field"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "agent_message_chunk"
                                       :content {:text "Hello"}}))]
      (is (= "Hello" (get-in result [:update :content :text]))))))

(deftest acp-coerces-websearch-tool-call
  (testing "raw-input.query survives coercion on :tool-call-update"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "tool_call_update"
                                       :toolCallId    "toolu_abc"
                                       :rawInput      {:query "CRDT vs OT"}
                                       :meta          {:claudeCode {:toolName "WebSearch"}}}))]
      (is (= "CRDT vs OT" (get-in result [:update :raw-input :query]))))))

(deftest acp-coerces-agent-thought-chunk
  (testing "happy path: chunk text accessible at :content :text"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "agent_thought_chunk"
                                       :content {:text "The user wants" :type "text"}}))]
      (is (= :agent-thought-chunk (get-in result [:update :session-update])))
      (is (= "The user wants" (get-in result [:update :content :text]))))))

(deftest acp-coerces-tool-call
  (testing "happy path: camelCase keys and nested meta converted"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "tool_call"
                                       :toolCallId "toolu_01"
                                       :meta {:claudeCode {:toolName "Read"}}}))
          update (:update result)]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= :tool-call (:session-update update)))
      (is (= "toolu_01" (:tool-call-id update)))
      (is (= "Read" (get-in update [:meta :claude-code :tool-name])))))

  (testing "tolerates absent optional fields (non-file tools like WebSearch)"
    ;; Regression guard: 1a4af57 — tool_call without :kind/:status/:content/:locations
    ;; was silently aborted instead of reaching resolve-permission.
    (let [update (-> (acp-codec/acp-session-update-from-js
                       (session-update-js {:sessionUpdate "tool_call"
                                           :toolCallId "toolu_websearch"}))
                     :update)]
      (is (= :tool-call (:session-update update)))
      (is (nil? (:kind update))))))

(deftest acp-coerces-tool-call-update
  (testing "happy path: session-update keyword and tool-call-id preserved"
    (let [update (-> (acp-codec/acp-session-update-from-js
                       (session-update-js {:sessionUpdate "tool_call_update"
                                           :toolCallId "toolu_01"}))
                     :update)]
      (is (= :tool-call-update (:session-update update)))
      (is (= "toolu_01" (:tool-call-id update)))))

  (testing "nil :content coerces to nil (nil-safe for diffs consumer)"
    (let [update (-> (acp-codec/acp-session-update-from-js
                       (session-update-js {:sessionUpdate "tool_call_update"
                                           :toolCallId "toolu_02"
                                           :content nil}))
                     :update)]
      (is (nil? (:content update)))))

  (testing "unknown content :type passes through as opaque map"
    (let [update (-> (acp-codec/acp-session-update-from-js
                       (session-update-js {:sessionUpdate "tool_call_update"
                                           :toolCallId "toolu_unknown"
                                           :content [{:type "unknown_future_type"
                                                      :data "something"}]}))
                     :update)]
      (is (= [{:type "unknown_future_type" :data "something"}]
             (:content update))))))

(deftest acp-coerces-usage-update
  (testing "happy path: dispatch-only (prevents schema-rejection log spam per #244)"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "usage_update"
                                       :used 1234
                                       :size 200000}))]
      (is (= :usage-update (get-in result [:update :session-update]))))))

(deftest acp-coerces-available-commands-update
  (testing "happy path: dispatch-only (SDK variant recognised without throwing)"
    (let [result (acp-codec/acp-session-update-from-js
                   (session-update-js {:sessionUpdate "available_commands_update"}))]
      (is (= :available-commands-update (get-in result [:update :session-update]))))))

(deftest acp-coerces-permission-request
  (testing "happy path: :kind and :tool-call-id coerce from camelCase"
    (let [result (acp-codec/acp-permission-request-from-js
                   (permission-request-js {:toolCallId "toolu_perm_01"
                                           :kind "edit"}))]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= "toolu_perm_01" (get-in result [:tool-call :tool-call-id])))
      (is (= "edit" (get-in result [:tool-call :kind])))))

  (testing "throws without :kind (required for permission resolver)"
    (is (try
          (acp-codec/acp-permission-request-from-js
            (permission-request-js {:toolCallId "toolu_no_kind"}))
          false
          (catch :default _ true)))))

;; ========================================
;; Section 2: Consumer Helpers
;; ========================================

(def ^:private acp-text-chunks
  {:agent-message-chunk {:text "Hello" :type "text"}
   :agent-thought-chunk {:text "The user wants" :type "text"}})

(deftest acp-update-text-extracts-chunk-text
  (doseq [chunk-type (keys acp-text-chunks)]
    (testing (str "extracts text from " (name chunk-type) " update")
      (let [content (get acp-text-chunks chunk-type)]
        (is (= (:text content)
               (acp-codec/acp-update-text {:session-update chunk-type
                                           :content content}))))))

  (testing "returns nil for updates without text content"
    (is (nil? (acp-codec/acp-update-text
                {:session-update :available-commands-update
                 :available-commands []})))))

(deftest edit-diffs-from-completion
  (testing "extracts diff items from completed tool-call-update"
    (let [update {:session-update :tool-call-update
                  :content [{:type "diff" :path "/a.md"
                             :old-text "old" :new-text "new"}
                            {:type "text" :text "some output"}
                            {:type "diff" :path "/b.md"
                             :old-text "before" :new-text "after"}]}]
      (is (= [{:type "diff" :path "/a.md" :old-text "old" :new-text "new"}
              {:type "diff" :path "/b.md" :old-text "before" :new-text "after"}]
             (acp-codec/edit-diffs update)))))

  (testing "returns nil for streaming refinement events (:kind present)"
    (is (nil? (acp-codec/edit-diffs
                {:session-update :tool-call-update
                 :kind "edit"
                 :content [{:type "diff" :path "/a.md"
                            :old-text "old" :new-text "new"}]}))))

  (testing "returns nil for :tool-call request events (not updates)"
    (is (nil? (acp-codec/edit-diffs
                {:session-update :tool-call
                 :content [{:type "diff" :path "/a.md"
                            :old-text "old" :new-text "new"}]}))))

  (testing "returns nil when no diff items present"
    (are [update] (nil? (acp-codec/edit-diffs update))
      {:session-update :tool-call-update :content [{:type "text" :text "hi"}]}
      {:session-update :tool-call-update :content nil}
      {:session-update :tool-call-update :content []})))

(deftest read-completed-guards
  (testing "true for Read tool-call-update with file metadata"
    (is (acp-codec/read-completed?
          {:session-update :tool-call-update
           :tool-call-id "toolu_01"
           :meta {:claude-code {:tool-name "Read"
                                :tool-response {:file {:filePath "/a.md" :totalLines 1}}}}})))

  (testing "false for Read tool-call-update without file metadata (pre-completion event)"
    (is (not (acp-codec/read-completed?
               {:session-update :tool-call-update
                :tool-call-id "toolu_01"
                :meta {:claude-code {:tool-name "Read"}}}))))

  (testing "false for non-Read tool-call-update that happens to carry file metadata"
    (is (not (acp-codec/read-completed?
               {:session-update :tool-call-update
                :tool-call-id "toolu_01"
                :meta {:claude-code {:tool-name "Edit"
                                     :tool-response {:file {:filePath "/a.md" :totalLines 1}}}}})))))

(deftest acp-read-display-label-from-meta
  (testing "formats filename and line count from tool-response meta"
    (is (= "Read — document.md (37 lines)"
           (acp-codec/acp-read-display-label
             {:session-update :tool-call-update
              :tool-call-id "toolu_01Ext"
              :meta {:claude-code {:tool-name "Read"
                                   :tool-response {:file {:filePath "/path/to/document.md"
                                                          :totalLines 37
                                                          :numLines 37
                                                          :startLine 1}
                                                   :type "text"}}}}))))

  (testing "returns nil when tool-response meta absent"
    (is (nil? (acp-codec/acp-read-display-label
                {:session-update :tool-call-update
                 :tool-call-id "toolu_01Ext"
                 :meta {:claude-code {:tool-name "Read"}}})))))
