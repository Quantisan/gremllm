(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]))

(deftest set-topic-test
  (testing "set-topic converts JS object, normalizes it, and returns a save effect"
    (let [topic-js (clj->js {:id "t1"
                             :name "Test Topic"
                             :messages [(clj->js {:id "m1" :type "user" :content "Hi"})]})
          expected-topic {:id "t1"
                          :name "Test Topic"
                          :messages [{:id "m1" :type :user :content "Hi"}]}
          effects (topic/set-topic {} topic-js)]
      (is (= [[:effects/save [:topic] expected-topic]]
             effects))))

  (testing "set-topic returns nil if input is nil"
    (is (nil? (topic/set-topic {} nil)))))

(deftest restore-or-create-topic-test
  (testing "dispatches :topic.actions/set when a topic is provided"
    (let [loaded-topic {:id "t1"}]
      (is (= [[:topic.actions/set loaded-topic]]
             (topic/restore-or-create-topic {} loaded-topic)))))

  (testing "dispatches :topic.actions/start-new when topic is nil"
    (is (= [[:topic.actions/start-new]]
           (topic/restore-or-create-topic {} nil)))))

(deftest start-new-topic-test
  (testing "returns a save effect with a new topic structure"
    (let [effects (topic/start-new-topic {})
          new-topic (topic/create-topic)]
      (is (= [[:effects/save [:topic] new-topic]]
             effects)))))
