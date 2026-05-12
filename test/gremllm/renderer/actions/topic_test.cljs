(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [malli.core :as m])
  (:require-macros [gremllm.test-utils :refer [with-console-error-silenced]]))

(deftest start-new-topic-test
  (let [result (topic/start-new-topic {})
        [[_ topic-path saved-topic] [action-name active-id]] result]

    (is (= 2 (count result)) "should return exactly two effects")

    (is (= :effects/save (first (first result))) "first should be save effect")
    (is (m/validate schema/Topic saved-topic) "saved topic should be valid per schema")
    (is (= (topic-state/topic-path (:id saved-topic)) topic-path) "should save to correct topics path")

    (is (= :topic.actions/set-active action-name) "second should be set-active action")
    (is (= (:id saved-topic) active-id) "should set same topic ID as active")))

(def ^:private expected-new-topic (schema/create-topic))

(deftest normalize-topic-test
  (let [denormalized (assoc expected-new-topic
                            :messages [{:id 1 :type "user" :text "test"}
                                       {:id 2 :type "assistant" :text "response"}])
        expected     (assoc expected-new-topic
                            :messages [{:id 1 :type :user :text "test"}
                                       {:id 2 :type :assistant :text "response"}])]
    (is (= expected (codec/topic-from-ipc denormalized))
        "should convert message types from strings to keywords")))

(deftest commit-rename-test
  (let [topic-id "topic-123"
        state    {:topics {topic-id {:id topic-id :name "Original Name"}}}]

    (testing "blank name - should exit rename mode without saving"
      (let [actions (topic/commit-rename state topic-id "   ")]
        (is (= 1 (count actions)))
        (is (= :ui.actions/exit-topic-rename-mode (ffirst actions)))))

    (testing "unchanged name - should exit rename mode without saving"
      (let [actions (topic/commit-rename state topic-id "Original Name")]
        (is (= 1 (count actions)))
        (is (= :ui.actions/exit-topic-rename-mode (ffirst actions)))))

    (testing "valid new name - should save, exit rename mode, and auto-save"
      (let [actions (topic/commit-rename state topic-id "New Name")]
        (is (= 3 (count actions)))
        (is (= :topic.actions/set-name (ffirst actions)))
        (is (= "New Name" (nth (first actions) 2)))
        (is (= :ui.actions/exit-topic-rename-mode (first (second actions))))
        (is (= :topic.effects/auto-save (first (nth actions 2))))))))

(deftest auto-save-test
  (let [topic-id       "topic-123"
        empty-topic    {:id topic-id :messages []}
        topic-with-msg {:id topic-id :messages [{:id "m1" :type :user :content "hi"}]}]

    (testing "should not trigger save when topic has no messages"
      (let [state  {:topics {topic-id empty-topic}}
            actions (topic/auto-save state topic-id)]
        (is (nil? actions))))

    (testing "should trigger save when topic has messages"
      (let [state  {:topics {topic-id topic-with-msg}}
            actions (topic/auto-save state topic-id)]
        (is (= [[:topic.effects/save-topic topic-id]] actions))))))

(def sample-excerpt
  {:id "e1"
   :text "hello"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph
                           :index 2
                           :start-line 3
                           :end-line 3
                           :block-text-snippet "hello world"}
             :end-block {:kind :paragraph
                         :index 2
                         :start-line 3
                         :end-line 3
                         :block-text-snippet "hello world"}}})

(deftest delete-topic-success-test
  (testing "triggers workspace reload after successful deletion"
    (let [topic-id "topic-123"
          state    {}
          actions  (topic/delete-topic-success state topic-id)]
      (is (= [[:workspace.effects/reload]] actions)
          "should return workspace reload effect"))))

(deftest delete-topic-error-test
  (testing "logs error and returns empty actions"
    (with-console-error-silenced
      (let [topic-id "topic-123"
            state    {}
            error    (js/Error. "deletion failed")
            actions  (topic/delete-topic-error state topic-id error)]
        (is (= [] actions)
            "should return empty actions vector")))))

(deftest auto-save-fires-when-excerpts-present-with-no-messages-test
  (let [state {:active-topic-id "t1"
               :topics {"t1" {:id "t1"
                              :messages []
                              :excerpts [sample-excerpt]}}}]
    (is (= [[:topic.effects/save-topic "t1"]]
           (topic/auto-save state "t1")))))

(deftest finalize-turn-test
  (let [topic-id "t1"]
    (is (= [[:excerpt.actions/consume topic-id]
            [:topic.actions/mark-unsaved topic-id]
            [:topic.effects/auto-save topic-id]]
           (topic/finalize-turn {} topic-id)))))

(deftest start-tool-call-test
  (testing "emits add-to-chat-no-save with a valid :tool-call message"
    (let [effects (topic/start-tool-call
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
             effects))))

  (testing "throws if message fails Message schema validation"
    (is (try
          (topic/start-tool-call
            {:active-topic-id "t1"}
            ;; missing required :tool-call-id
            {:id 1 :type :tool-call :tool :web-search
             :tool-call-status "pending" :text ""})
          false
          (catch :default _ true)))))

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
      (let [effects (topic/update-tool-call state "toolu_1"
                                            {:tool-call-status "completed"
                                             :query            "CRDT vs OT"})]
        (is (= #{[:effects/save [:topics "t1" :messages 1 :tool-call-status] "completed"]
                 [:effects/save [:topics "t1" :messages 1 :query]            "CRDT vs OT"]}
               (set effects)))))

    (testing "returns nil when no message matches the tool-call-id"
      (is (nil? (topic/update-tool-call state "toolu_missing"
                                        {:tool-call-status "completed"}))))))
