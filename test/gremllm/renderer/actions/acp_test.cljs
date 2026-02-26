(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [are deftest is testing]]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [gremllm.renderer.actions.acp :as acp]))

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
      (is (= [[:messages.actions/add-to-chat-no-save {:id 456
                                                      :type :assistant
                                                      :text "Hi!"}]]
             effects)))))

(deftest test-handle-tool-event
  (testing "creates tool-use message for tool-call"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call
                     :kind "edit"
                     :title "Edit File"
                     :locations [{:path "src/gremllm/schema.cljs"}]}
                    789)
          action (first effects)
          message (nth action 1)]
      (is (= :messages.actions/add-to-chat-no-save (first action)))
      (is (= :tool-use (:type message)))
      (is (= "Edit File — src/gremllm/schema.cljs" (:text message)))
      (is (= 789 (:id message)))))

  (testing "returns nil for tool-call with kind read (skipped at request phase)"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call
                     :tool-call-id "toolu_01Ext"
                     :kind "read"
                     :status "pending"
                     :title "Read File"
                     :raw-input {:file-path "/path/to/document.md"}
                     :content []
                     :locations []}
                    789)]
      (is (nil? effects))))

  (testing "returns tool-use for tool-call-update with Read tool-response meta"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_01Ext"
                     :meta {:claude-code {:tool-name "Read"
                                          :tool-response {:file {:filePath "/path/to/document.md"
                                                                 :totalLines 37
                                                                 :numLines 37
                                                                 :startLine 1}
                                                          :type "text"}}}}
                    456)
          action (first effects)
          message (nth action 1)]
      (is (= :messages.actions/add-to-chat-no-save (first action)))
      (is (= :tool-use (:type message)))
      (is (= "Read — document.md (37 lines)" (:text message)))
      (is (= 456 (:id message)))))

  (testing "returns tool-use Read — completed for tool-call-update with Read tool-name + completed status"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_01Ext"
                     :status "completed"
                     :meta {:claude-code {:tool-name "Read"}}
                     :content [{:type "content" :content {:type "text" :text "file contents..."}}]
                     :raw-output "file contents..."}
                    789)
          action (first effects)
          message (nth action 1)]
      (is (= :messages.actions/add-to-chat-no-save (first action)))
      (is (= :tool-use (:type message)))
      (is (= "Read — completed" (:text message)))
      (is (= 789 (:id message)))))

  (testing "dispatches pending diffs from tool-call-update with diff content"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_1"
                     :status "completed"
                     :content [{:type "diff" :path "/tmp/test.md"
                                :old-text "old" :new-text "new"}]}
                    123)]
      (is (= [[:document.actions/append-pending-diffs
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
