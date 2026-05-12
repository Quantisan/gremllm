(ns gremllm.renderer.actions.tool-call-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.tool-call :as tool-call]
            [gremllm.renderer.state.topic :as topic-state]))

(deftest start-tool-call-test
  (testing "emits add-to-chat-no-save with a valid :tool-call message"
    (let [effects (tool-call/start-tool-call
                    {:active-topic-id "t1"}
                    {:id 123
                     :type :tool-call
                     :tool-call-id "toolu_x"
                     :tool :web-search
                     :tool-call-status "pending"
                     :text ""
                     :query nil})]
      (is (= [[:messages.actions/add-to-chat-no-save "t1"
               {:id 123
                :type :tool-call
                :tool-call-id "toolu_x"
                :tool :web-search
                :tool-call-status "pending"
                :text ""
                :query nil}]]
             effects)))))

(deftest update-tool-call-test
  (let [state {:topics {"t1" {:messages [{:type :user :text "q"}
                                         {:type :tool-call
                                          :tool-call-id "toolu_1"
                                          :tool :web-search
                                          :tool-call-status "pending"
                                          :query nil
                                          :text ""}]}}
               :active-topic-id "t1"}]
    (testing "emits path-based saves for each field in patch"
      (let [effects (tool-call/update-tool-call state "toolu_1"
                                                {:tool-call-status "completed"
                                                 :query            "CRDT vs OT"})]
        (is (= #{[:effects/save [:topics "t1" :messages 1 :tool-call-status] "completed"]
                 [:effects/save [:topics "t1" :messages 1 :query]            "CRDT vs OT"]}
               (set effects)))))

    (testing "returns nil when no message matches the tool-call-id"
      (is (nil? (tool-call/update-tool-call state "toolu_missing"
                                            {:tool-call-status "completed"}))))))
