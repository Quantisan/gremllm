(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.acp :as acp]
            [gremllm.renderer.state.acp :as acp-state]))

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
          effects (acp/streaming-chunk-effects state "there!" 123)]
      (is (= [[:effects/save acp-state/loading-path false]
              [:effects/save [:topics "t1" :messages 1 :text] "Hi there!"]]
             effects))))

  (testing "starts new assistant message when last is user"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}]}}
                 :active-topic-id "t1"}
          effects (acp/streaming-chunk-effects state "Hi!" 456)]
      (is (= [[:effects/save acp-state/loading-path false]
              [:messages.actions/append-to-state {:id 456
                                                  :type :assistant
                                                  :text "Hi!"}]
              [:topic.actions/mark-active-unsaved]
              [:ui.actions/scroll-chat-to-bottom]]
             effects)))))
