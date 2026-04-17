(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [are deftest is testing]]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [gremllm.renderer.actions.acp :as acp]
            [gremllm.schema-test :as schema-test]))

(deftest test-append-to-response
  (testing "appends chunk text to last message in active topic"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}
                                           {:type :assistant :text "Hi "}]}}
                 :active-topic-id "t1"}
          effect (acp/append-to-response state "there!")]
      (is (= [:effects/save [:topics "t1" :messages 1 :text] "Hi there!"]
             effect)))))

(deftest test-streaming-chunk-effects
  (testing "appends chunk to existing assistant message"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}
                                           {:type :assistant :text "Hi "}]}}
                 :active-topic-id "t1"}
          effects (acp/streaming-chunk-effects state :assistant "there!" 123)]
      (is (= [[:effects/save [:topics "t1" :messages 1 :text] "Hi there!"]]
             effects))))

  (testing "starts new assistant message when last is user"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}]}}
                 :active-topic-id "t1"}
          effects (acp/streaming-chunk-effects state :assistant "Hi!" 456)]
      (is (= [[:messages.actions/add-to-chat-no-save "t1" {:id 456
                                                           :type :assistant
                                                           :text "Hi!"}]]
             effects)))))

(deftest test-handle-tool-event
  (testing "returns tool-use for Read tool-call-update with file payload"
    (let [effects (acp/handle-tool-event
                    {:active-topic-id "t1"}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_01U3ze1LsKXNhkBj46DM6SPN"
                     :meta {:claude-code {:tool-name "Read"
                                          :tool-response {:file {:filePath "/Users/paul/Projects/gremllm/resources/gremllm-launch-log.md"
                                                                 :totalLines 16}
                                                          :type "text"}}}}
                    456)]
      (is (= [[:messages.actions/add-to-chat-no-save "t1"
               {:id 456
                :type :tool-use
                :text "Read — gremllm-launch-log.md (16 lines)"}]]
             effects))))

  (testing "dispatches pending diffs from tool-call-update with diff content"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_1"
                     :status "completed"
                     :content [{:type "diff" :path "/tmp/test.md"
                                :old-text "old" :new-text "new"}]}
                    123)]
      (is (= [[:topic.actions/append-pending-diffs
               [{:type "diff" :path "/tmp/test.md"
                 :old-text "old" :new-text "new"}]]]
             effects))))

  (testing "returns nil for tool-call-update without diffs and no Read meta"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_1"
                     :status "completed"
                     :meta {:claude-code {:tool-name "Edit"}}}
                    123)]
      (is (nil? effects))))

  (testing "ignores non-tool update types"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :agent-message-chunk
                     :content {:type "text" :text "hello"}}
                    123)]
      (is (nil? effects)))))

(deftest test-session-update-routing
  (let [state {:topics {"t1" {:messages [{:type :assistant :text "Hi "}]}}
               :active-topic-id "t1"}]
    (with-redefs [schema/generate-message-id (constantly 1)
                  codec/acp-update-text      (constantly "text")
                  acp/streaming-chunk-effects (fn [_ msg-type _ _] [[:streamed msg-type]])
                  acp/handle-tool-event       (fn [_ update _]     [[:tooled (:session-update update)]])]

      (testing "routes each update type to the correct handler"
        (are [update-type expected]
          (= expected (acp/session-update state {:update {:session-update update-type
                                                          :content {:type "text" :text "x"}}}))
          :agent-message-chunk [[:streamed :assistant]]
          :agent-thought-chunk [[:streamed :reasoning]]
          :tool-call           [[:tooled :tool-call]]
          :tool-call-update    [[:tooled :tool-call-update]]))

      (testing "ignores unsupported update types"
        (is (nil? (acp/session-update state {:update {:session-update :available-commands-update}})))))))

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
