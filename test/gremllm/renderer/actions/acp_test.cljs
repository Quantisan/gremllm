(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [are deftest is testing]]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [gremllm.renderer.actions.acp :as acp]
            [gremllm.renderer.state.acp :as acp-state]
            [gremllm.schema-test :as schema-test]))

(deftest test-append-to-response
  (testing "appends chunk text to last message in active topic"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}
                                           {:type :assistant :text "Hi "}]}}
                 :active-topic-id "t1"}
          effect (acp/append-to-response state "there!")]
      (is (= [:effects/save [:topics "t1" :messages 1 :text] "Hi there!"]
             effect)))))

(deftest test-append-streaming-text
  (testing "continues by appending text to the last message of matching type"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}
                                           {:type :assistant :text "Hi "}]}}
                 :active-topic-id "t1"}
          effects (with-redefs [schema/generate-message-id (constantly 123)]
                    (acp/append-streaming-text
                      state
                      {:session-update :agent-message-chunk
                       :content {:text "there!" :type "text"}}))]
      (is (= [[:effects/save [:topics "t1" :messages 1 :text] "Hi there!"]]
             effects))))

  (testing "starts a new assistant message when the last message is a different type"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}]}}
                 :active-topic-id "t1"}
          effects (with-redefs [schema/generate-message-id (constantly 456)]
                    (acp/append-streaming-text
                      state
                      {:session-update :agent-message-chunk
                       :content {:text "Hi!" :type "text"}}))]
      (is (= [[:messages.actions/add-to-chat-no-save "t1"
               {:id 456 :type :assistant :text "Hi!"}]]
             effects))))

  (testing "starts a new reasoning message from an :agent-thought-chunk"
    (let [state {:topics {"t1" {:messages []}}
                 :active-topic-id "t1"}
          effects (with-redefs [schema/generate-message-id (constantly 789)]
                    (acp/append-streaming-text
                      state
                      {:session-update :agent-thought-chunk
                       :content {:text "thinking..." :type "text"}}))]
      (is (= [[:messages.actions/add-to-chat-no-save "t1"
               {:id 789 :type :reasoning :text "thinking..."}]]
             effects)))))

(deftest test-start-web-search-message
  (testing "mints a pending :web-search :tool-call message"
    (let [effects (with-redefs [schema/generate-message-id (constantly 999)]
                    (acp/start-web-search-message
                      {}
                      {:session-update :tool-call
                       :tool-call-id   "toolu_ws"
                       :status         "pending"
                       :raw-input      {}
                       :meta           {:claude-code {:tool-name "WebSearch"}}}))]
      (is (= [[:tool-call.actions/start
               {:id 999
                :type :tool-call
                :tool-call-id "toolu_ws"
                :tool :web-search
                :tool-call-status "pending"
                :query nil
                :text ""}]]
             effects))))

  (testing "defaults :tool-call-status to \"pending\" when wire :status is absent"
    (let [effects (with-redefs [schema/generate-message-id (constantly 1)]
                    (acp/start-web-search-message
                      {}
                      {:session-update :tool-call
                       :tool-call-id   "toolu_ws2"
                       :meta           {:claude-code {:tool-name "WebSearch"}}}))]
      (is (= "pending" (-> effects first second :tool-call-status))))))

(deftest test-update-web-search-message
  (testing "emits a :query patch when :raw-input.:query is present"
    (let [effects (acp/update-web-search-message
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id   "toolu_ws"
                     :raw-input      {:query "CRDT vs OT"}
                     :meta           {:claude-code {:tool-name "WebSearch"}}})]
      (is (= [[:tool-call.actions/update "toolu_ws" {:query "CRDT vs OT"}]]
             effects))))

  (testing "emits a :tool-call-status patch when wire :status is present"
    (let [effects (acp/update-web-search-message
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id   "toolu_ws"
                     :status         "completed"
                     :meta           {:claude-code {:tool-name "WebSearch"}}})]
      (is (= [[:tool-call.actions/update "toolu_ws" {:tool-call-status "completed"}]]
             effects))))

  (testing "returns nil when no patchable fields are present"
    (is (nil? (acp/update-web-search-message
                {}
                {:session-update :tool-call-update
                 :tool-call-id   "toolu_ws"
                 :meta           {:claude-code {:tool-name "WebSearch"}}})))))

(deftest test-record-read-message
  (testing "mints a one-shot completed :read :tool-call message from file metadata"
    (let [effects (with-redefs [schema/generate-message-id (constantly 456)]
                    (acp/record-read-message
                      {}
                      {:session-update :tool-call-update
                       :tool-call-id   "toolu_01U3ze1LsKXNhkBj46DM6SPN"
                       :meta {:claude-code {:tool-name "Read"
                                            :tool-response {:file {:filePath "/Users/paul/Projects/gremllm/resources/gremllm-launch-log.md"
                                                                   :totalLines 16}
                                                            :type "text"}}}}))]
      (is (= [[:tool-call.actions/start
               {:id 456
                :type :tool-call
                :tool-call-id "toolu_01U3ze1LsKXNhkBj46DM6SPN"
                :tool :read
                :tool-call-status "completed"
                :text "Read — gremllm-launch-log.md (16 lines)"}]]
             effects)))))

(deftest test-append-edit-diffs
  (testing "dispatches :topic.actions/append-pending-diffs with extracted diffs"
    (let [effects (acp/append-edit-diffs
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id   "toolu_1"
                     :status         "completed"
                     :content        [{:type "diff" :path "/tmp/test.md"
                                       :old-text "old" :new-text "new"}]})]
      (is (= [[:topic.actions/append-pending-diffs
               [{:type "diff" :path "/tmp/test.md"
                 :old-text "old" :new-text "new"}]]]
             effects)))))

(deftest test-session-update-routing
  (with-redefs [acp/append-streaming-text     (fn [_ update] [[:streamed (:session-update update)]])
                acp/start-web-search-message  (fn [_ _]      [[:web-search-started]])
                acp/update-web-search-message (fn [_ _]      [[:web-search-updated]])
                acp/record-read-message       (fn [_ _]      [[:read-completed]])
                acp/append-edit-diffs         (fn [_ _]      [[:edit-completed]])]

    (testing "routes streaming text chunks to append-streaming-text"
      (are [update-type] (= [[:streamed update-type]]
                            (acp/session-update {} {:update {:session-update update-type
                                                             :content {:type "text" :text "x"}}}))
        :agent-message-chunk
        :agent-thought-chunk))

    (testing "routes WebSearch :tool-call to start-web-search-message"
      (is (= [[:web-search-started]]
             (acp/session-update {} {:update {:session-update :tool-call
                                              :tool-call-id "toolu_ws"
                                              :meta {:claude-code {:tool-name "WebSearch"}}}}))))

    (testing "routes WebSearch :tool-call-update to update-web-search-message"
      (is (= [[:web-search-updated]]
             (acp/session-update {} {:update {:session-update :tool-call-update
                                              :tool-call-id "toolu_ws"
                                              :meta {:claude-code {:tool-name "WebSearch"}}}}))))

    (testing "routes Read :tool-call-update with file metadata to record-read-message"
      (is (= [[:read-completed]]
             (acp/session-update {} {:update {:session-update :tool-call-update
                                              :tool-call-id "toolu_r"
                                              :meta {:claude-code {:tool-name "Read"
                                                                   :tool-response {:file {:filePath "/a.md"
                                                                                          :totalLines 1}}}}}}))))

    (testing "routes diff-bearing :tool-call-update to append-edit-diffs"
      (is (= [[:edit-completed]]
             (acp/session-update {} {:update {:session-update :tool-call-update
                                              :tool-call-id "toolu_d"
                                              :content [{:type "diff" :path "/a.md" :new-text "x"}]}}))))

    (testing "ignores unsupported update types"
      (is (nil? (acp/session-update {} {:update {:session-update :available-commands-update}}))))))

(deftest send-prompt-forwards-structured-user-message-with-excerpts-to-acp-prompt-test
  (let [old-window (.-window js/globalThis)
        captured-call (atom nil)
        promise (js/Promise.resolve nil)]
    (aset js/globalThis
          "window"
          #js {:electronAPI #js {:acpPrompt (fn [& args]
                                              (let [[acp-session-id payload] args]
                                                (reset! captured-call
                                                        {:argc  (count args)
                                                         :session-id acp-session-id
                                                         :payload payload})
                                                promise))}})
    (try
      (let [message (schema-test/create-message
                      {:id 1
                       :type :user
                       :text "reword these"
                       :context {:excerpts [{:id "e1"
                                             :text "x"
                                             :locator {:document-relative-path "document.md"
                                                       :start-block {:kind :paragraph
                                                                     :index 2
                                                                     :start-line 3
                                                                     :end-line 3
                                                                     :block-text-snippet "x"}
                                                       :end-block {:kind :paragraph
                                                                   :index 2
                                                                   :start-line 3
                                                                   :end-line 3
                                                                   :block-text-snippet "x"}}}]}})
            state {:active-topic-id "t1"
                   :topics {"t1" {:id "t1" :session {:id "s1"}}}}
            _effects (acp/send-prompt state message)]
        (is (= 2 (:argc @captured-call))
            "send-prompt should invoke acpPrompt with only the session id and message payload")
        (is (= "s1" (:session-id @captured-call))
            "send-prompt should use the active topic's ACP session id")
        (is (= message
               (codec/user-message-from-ipc (:payload @captured-call)))
            "send-prompt should forward the full structured user message, including excerpt context, across the IPC boundary"))
      (finally
        (if (nil? old-window)
          (js-delete js/globalThis "window")
          (aset js/globalThis "window" old-window))))))

(deftest resume-or-new-session-test
  (let [base {:topics {"t1" {:id "t1" :session {}}}}]
    (testing "new session when topic has no acp session id"
      (is (= [[:acp.actions/new-session "t1"]] (acp/resume-or-new-session base "t1"))))
    (testing "resume when a persisted acp session id exists"
      (let [state (assoc-in base [:topics "t1" :session :id] "s1")]
        (is (= [[:acp.actions/resume-session "t1" "s1"]] (acp/resume-or-new-session state "t1")))))
    (testing "no-op when already live this run"
      (let [state (assoc-in base acp-state/live-topics-path #{"t1"})]
        (is (nil? (acp/resume-or-new-session state "t1")))))
    (testing "no-op while init is in flight"
      (let [state (assoc-in base [:loading "t1"] true)]
        (is (nil? (acp/resume-or-new-session state "t1")))))))

(deftest mark-topic-live-test
  (testing "adds topic to the live set"
    (is (some #(= [:effects/save acp-state/live-topics-path #{"t1"}] %)
              (acp/mark-topic-live {} "t1"))))
  (testing "preserves existing live topics"
    (let [state (assoc-in {} acp-state/live-topics-path #{"t2"})]
      (is (some #(= [:effects/save acp-state/live-topics-path #{"t1" "t2"}] %)
                (acp/mark-topic-live state "t1"))))))

(deftest session-ready-marks-live-test
  (testing "emits the mark-topic-live action for the topic"
    (is (some #(= [:acp.actions/mark-topic-live "t1"] %)
              (acp/session-ready {} "t1" "s1")))))

(deftest append-edit-diffs-suppression-test
  (testing "skips diffs whose tool-call-id is already in :resolved-tool-calls"
    (let [topic-id "t1"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs []
                                                 :resolved-tool-calls #{"tc-1"}}}}}
          update   {:session-update :tool-call-update
                    :tool-call-id   "tc-1"
                    :content        [{:type "diff" :path "/p" :old-text "a" :new-text "b"}]}]
      (is (nil? (acp/append-edit-diffs state update))
          "PostToolUse for already-resolved tool-call should be ignored")))
  (testing "passes through diffs for unresolved tool-call-id (existing path)"
    (let [topic-id "t1"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs []
                                                 :resolved-tool-calls #{}}}}}
          update   {:session-update :tool-call-update
                    :tool-call-id   "tc-2"
                    :content        [{:type "diff" :path "/p" :old-text "a" :new-text "b"}]}
          actions  (acp/append-edit-diffs state update)]
      (is (= 1 (count actions)))
      (is (= :topic.actions/append-pending-diffs (ffirst actions))))))
