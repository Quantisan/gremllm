(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
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
                     :title "Read File"
                     :locations [{:path "src/gremllm/schema.cljs"}]}
                    789)
          action (first effects)
          message (nth action 1)]
      (is (= :messages.actions/add-to-chat-no-save (first action)))
      (is (= :tool-use (:type message)))
      (is (= "Read File — src/gremllm/schema.cljs" (:text message)))
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

  (testing "returns nil for tool-call-update without diffs"
    (let [effects (acp/handle-tool-event
                    {}
                    {:session-update :tool-call-update
                     :tool-call-id "toolu_1"
                     :status "completed"}
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
               :active-topic-id "t1"}
        streaming-calls (atom [])
        tool-calls (atom [])
        next-id 4242]
    (with-redefs [schema/generate-message-id
                  (fn [] next-id)

                  codec/acp-update-text
                  (fn [update]
                    (str "text:" (get-in update [:content :text])))

                  acp/streaming-chunk-effects
                  (fn [st message-type chunk-text message-id]
                    (swap! streaming-calls conj [st message-type chunk-text message-id])
                    [[:streaming message-type chunk-text message-id]])

                  acp/handle-tool-event
                  (fn [st update message-id]
                    (swap! tool-calls conj [st update message-id])
                    [[:tool (:session-update update) message-id]])]

      (testing "routes message chunk updates through streaming handler"
        (let [effects (acp/session-update state {:update {:session-update :agent-message-chunk
                                                          :content {:type "text" :text "hello"}}})]
          (is (= [[:streaming :assistant "text:hello" next-id]] effects))
          (is (= [[state :assistant "text:hello" next-id]] @streaming-calls))
          (is (empty? @tool-calls))))

      (testing "routes thought chunk updates to reasoning message type"
        (reset! streaming-calls [])
        (let [effects (acp/session-update state {:update {:session-update :agent-thought-chunk
                                                          :content {:type "text" :text "thinking"}}})]
          (is (= [[:streaming :reasoning "text:thinking" next-id]] effects))
          (is (= [[state :reasoning "text:thinking" next-id]] @streaming-calls))
          (is (empty? @tool-calls))))

      (testing "routes tool updates through tool-event handler"
        (reset! streaming-calls [])
        (let [update {:session-update :tool-call-update
                      :tool-call-id "toolu_1"}
              effects (acp/session-update state {:update update})]
          (is (= [[:tool :tool-call-update next-id]] effects))
          (is (= [[state update next-id]] @tool-calls))
          (is (empty? @streaming-calls))))

      (testing "returns nil for unsupported update types"
        (reset! streaming-calls [])
        (reset! tool-calls [])
        (let [effects (acp/session-update state {:update {:session-update :available-commands-update
                                                          :available-commands []}})]
          (is (nil? effects))
          (is (empty? @streaming-calls))
          (is (empty? @tool-calls)))))))
