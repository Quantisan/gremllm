(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
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

(deftest switch-topic-test
  (testing "switching active topic"
    (is (= [[:topic.actions/set-active "topic-2"]]
           (topic/switch-topic {} "topic-2"))
        "should dispatch set-active action")))
