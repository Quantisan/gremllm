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

;; TODO: refactor for pure fn
#_
(deftest test-add-message-effect
  (testing "messages are added under [:topic :messages]"
    (let [store (atom {})
          message {:id 1 :type :user :text "Hello"}]
      ;; Execute the effect directly
      ((:message.effects/add (nxr/get-registry)) nil store message)
      ;; Verify message was added to correct path
      (is (= [message] (get-in @store [:topic :messages]))))))
