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
  (testing "returns action to append message to topic messages"
    (let [state {:topic {:messages [{:id 1 :type :user :text "Hello"}]}}
          new-message {:id 2 :type :assistant :text "Hi there"}]
      (is (= [[:effects/save [:topic :messages] 
               [{:id 1 :type :user :text "Hello"}
                {:id 2 :type :assistant :text "Hi there"}]]]
             (msg/append-to-state state new-message))))))
