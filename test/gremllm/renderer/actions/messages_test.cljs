(ns gremllm.renderer.actions.messages-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.messages :as msg]))

;; TODO: Refactor test data to reduce DRY violations
;; 1. Repeated message structures - inline maps like {:type :user :text "..."} duplicated
;;    across tests
;; 2. Magic topic IDs and repetitive state setup - "t1", "topic-1" scattered throughout,
;;    same :topics {...} structure rebuilt in every test. Consider schema/create-topic
;; 3. Schema defaults unused - schema/create-topic and schema/PersistedTopic defaults
;;    exist but tests manually rebuild what schema already provides

(deftest test-build-conversation-with-new-message
  (testing "builds conversation from state with new message"
    (let [state {:topics {"t1" {:messages [{:text "first"}]}}
                 :active-topic-id "t1"}]
      (is (= [{:text "first"} {:text "second"}]
             (msg/build-conversation-with-new-message state {:text "second"}))))))

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
