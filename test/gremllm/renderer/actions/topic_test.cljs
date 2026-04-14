(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [gremllm.schema-test :as schema-fixtures]
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

(def ^:private topic-id "topic-123")
(def ^:private base-state {:active-topic-id topic-id
                           :topics {topic-id {:id topic-id :staged-selections []}}})

(deftest stage-test
  (let [selection schema-fixtures/single-word-selection
        [[effect path staged-vec]] (topic/stage base-state selection)
        item (first staged-vec)]

    (is (= :effects/save effect))
    (is (= (topic-state/staged-selections-path topic-id) path))
    (is (= 1 (count staged-vec)))
    (is (string? (:id item)))
    (is (clojure.string/starts-with? (:id item) "staged-"))
    (is (= selection (:selection item)))))

(deftest unstage-test
  (let [item-a {:id "staged-a" :selection schema-fixtures/single-word-selection}
        item-b {:id "staged-b" :selection schema-fixtures/mixed-format-selection}
        state  (assoc-in base-state [:topics topic-id :staged-selections] [item-a item-b])]

    (testing "removes item by id"
      (let [[[_ _ staged-vec]] (topic/unstage state "staged-a")]
        (is (= 1 (count staged-vec)))
        (is (= "staged-b" (:id (first staged-vec))))))

    (testing "no-op when id not found"
      (let [[[_ _ staged-vec]] (topic/unstage state "staged-unknown")]
        (is (= 2 (count staged-vec)))))))

(deftest clear-staged-test
  (let [item {:id "staged-a" :selection schema-fixtures/single-word-selection}
        state (assoc-in base-state [:topics topic-id :staged-selections] [item])
        [[_ path staged-vec]] (topic/clear-staged state)]

    (is (= (topic-state/staged-selections-path topic-id) path))
    (is (= [] staged-vec))))

(deftest clear-staged-across-topics-test
  (let [state {:topics {"t1" {:id "t1" :staged-selections [{:id "a"}]}
                        "t2" {:id "t2" :staged-selections [{:id "b"}]}}}
        effects (topic/clear-staged-across-topics state)]
    (is (= 2 (count effects)))
    (is (some #{[:effects/save (topic-state/staged-selections-path "t1") []]} effects))
    (is (some #{[:effects/save (topic-state/staged-selections-path "t2") []]} effects))))
