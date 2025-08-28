(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(deftest start-new-topic-test
  (let [result (topic/start-new-topic {})
        [[_ topic-path saved-topic] [_ active-path active-id]] result]

    (is (= 2 (count result)) "should return exactly two effects")
    (is (every? #(= :effects/save (first %)) result) "both should be save effects")

    (is (m/validate schema/Topic saved-topic) "saved topic should be valid per schema")
    (is (= [:topics (:id saved-topic)] topic-path) "should save to correct topics path")

    (is (= topic-state/active-topic-id-path active-path) "should save to active topic path")
    (is (= (:id saved-topic) active-id) "should set same topic ID as active")))

(def ^:private expected-new-topic (topic/create-topic))

(deftest set-topic-test
  (testing "when a valid topic is provided"
    (let [raw-topic  (assoc expected-new-topic
                            :messages [{:id "m1" :type "user" :content "Hi"}])
          test-topic-js (clj->js raw-topic)
          normalized-topic (topic/normalize-topic raw-topic)]
      (is (= [[:effects/save (conj topic-state/topics-path (:id raw-topic)) normalized-topic]
              [:effects/save topic-state/active-topic-id-path (:id raw-topic)]]
             (topic/set-topic {} test-topic-js))
          "should normalize the topic, save it to the topics map, and set it as active")))

  (testing "when input is nil"
    (is (nil? (topic/set-topic {} nil))
        "should return nil")))

(deftest normalize-topic-test
  (let [denormalized (assoc expected-new-topic
                            :messages [{:id "m1" :type "user"}
                                       {:id "m2" :type "assistant"}])
        expected     (assoc expected-new-topic
                            :messages [{:id "m1" :type :user}
                                       {:id "m2" :type :assistant}])]
    (is (= expected (topic/normalize-topic denormalized))
        "should convert message types from strings to keywords")))

(deftest switch-topic-test
  (testing "switching active topic"
    (is (= [[:effects/save topic-state/active-topic-id-path :topic-2]]
           (topic/switch-topic {} "topic-2"))
        "should dispatch an effect to update the active-topic-id")))
