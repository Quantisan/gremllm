(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.schema :as schema]
            [malli.core :as m]))

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
                            :messages [{:id "m1" :type "user"}
                                       {:id "m2" :type "assistant"}])
        expected     (assoc expected-new-topic
                            :messages [{:id "m1" :type :user}
                                       {:id "m2" :type :assistant}])]
    (is (= expected (schema/topic-from-ipc denormalized))
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
        (is (= :topic.actions/auto-save (first (nth actions 2))))))))

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

(deftest update-model-test
  (testing "updates active topic's model field"
    (let [topic-id "topic-123"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id :model "old-model"}}}
          actions  (topic/update-model state "new-model")]
      (is (= [[:effects/save [:topics topic-id :model] "new-model"]] actions)))))

