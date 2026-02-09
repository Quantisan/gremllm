(ns gremllm.renderer.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
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
