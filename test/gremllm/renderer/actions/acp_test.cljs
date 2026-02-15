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

(deftest test-session-update
  (testing "continues assistant streaming chunk"
    (let [state {:topics {"t1" {:messages [{:type :assistant :text "Hi "}]}}
                 :active-topic-id "t1"}
          effects (acp/session-update state {:update {:session-update :agent-message-chunk
                                                      :content {:type "text" :text "there"}}})]
      (is (= [[:effects/save [:topics "t1" :messages 0 :text] "Hi there"]]
             effects))))

  (testing "creates tool-use message for tool-call update"
    (let [state {:topics {"t1" {:messages [{:type :assistant :text "Done"}]}}
                 :active-topic-id "t1"}
          effects (acp/session-update state {:update {:session-update :tool-call
                                                      :title "Read File"
                                                      :locations [{:path "src/gremllm/schema.cljs"}]}})
          action (first effects)
          message (nth action 1)]
      (is (= :messages.actions/add-to-chat-no-save (first action)))
      (is (= :tool-use (:type message)))
      (is (= "Read File — src/gremllm/schema.cljs" (:text message)))
      (is (number? (:id message)))))

  (testing "logs tool-call-update without chat effects"
    (let [state {:topics {"t1" {:messages []}}
                 :active-topic-id "t1"}
          effects (acp/session-update state {:update {:session-update :tool-call-update
                                                      :tool-call-id "toolu_1"
                                                      :status "completed"}})]
      (is (nil? effects)))))
