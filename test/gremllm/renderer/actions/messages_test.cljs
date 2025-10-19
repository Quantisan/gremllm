(ns gremllm.renderer.actions.messages-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.messages :as msg]))

(deftest test-messages->api-format
  (testing "converts messages to API format"
    (is (= [{:role "user" :content "Hello"}
            {:role "assistant" :content "Hi there"}]
           (msg/messages->api-format
            [{:type :user :text "Hello"}
             {:type :assistant :text "Hi there"}])))))

(deftest test-append-to-state
  (testing "returns action to append message to the active topic's messages"
    (let [state {:topics {"topic-1" {:messages [{:id 1 :type :user :text "Hello"}]}}
                 :active-topic-id "topic-1"}
          new-message {:id 2 :type :assistant :text "Hi there"}]
      (is (= [[:effects/save [:topics "topic-1" :messages]
               [{:id 1 :type :user :text "Hello"}
                {:id 2 :type :assistant :text "Hi there"}]]]
             (msg/append-to-state state new-message)))))
  (testing "throws an error if no active topic is set"
    (let [state {:topics {"topic-1" {:messages []}}
                 :active-topic-id nil}
          new-message {:id 1, :text "test"}]
      (is (thrown-with-msg? js/Error #"Cannot append message: no active topic"
            (msg/append-to-state state new-message))))))

(deftest test-llm-response-received
  (testing "extracts message text from normalized LLMResponse"
    (let [assistant-id "asst-123"
          llm-response (clj->js {:text "Hello from AI"
                                 :usage {:input-tokens 10
                                         :output-tokens 5
                                         :total-tokens 15}})]
      (is (= [[:loading.actions/set-loading? assistant-id false]
              [:messages.actions/add-to-chat {:id "asst-123"
                                              :type :assistant
                                              :text "Hello from AI"}]]
             (msg/llm-response-received {} assistant-id llm-response))
          "Should extract text from :text field of normalized LLMResponse")))
  (testing "handles malformed response with missing text"
    (let [assistant-id "asst-123"
          api-response (clj->js {})]
      (is (= [[:loading.actions/set-loading? assistant-id false]
              [:messages.actions/add-to-chat {:id "asst-123"
                                              :type :assistant
                                              :text nil}]]
             (msg/llm-response-received {} assistant-id api-response))
          "Missing text should result in nil text, not throw"))))
