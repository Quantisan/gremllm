(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]))

(deftest test-topic-state-structure
  (testing "topic data lives under [:topic] key"
    (let [topic-data {:id "1" :name "Test" :messages []}]
      ;; Test set-topic returns correct effect
      (is (= [[:effects/save [:topic] topic-data]]
             (topic/set-topic {} (clj->js topic-data))))
      ;; Test start-new-topic saves to [:topic] path
      (let [effects (topic/start-new-topic {})]
        (is (= :effects/save (ffirst effects)))
        (is (= [:topic] (second (first effects))))))))