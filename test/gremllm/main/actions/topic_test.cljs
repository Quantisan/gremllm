(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan structure"
    (let [topic {:id "topic-123"
                 :name "Test Topic"
                 :messages []}
          topics-dir "/test/dir"
          plan (topic/topic->save-plan topic topics-dir)]
      (is (= {:dir      topics-dir
              :filename "topic-123.edn"
              :filepath "/test/dir/topic-123.edn"
              :content  "{:id \"topic-123\", :name \"Test Topic\", :messages []}"
              :topic    topic}
             plan))))
  
  (testing "strips optional fields before saving"
    (let [topic {:id "topic-123"
                 :name "Test Topic"
                 :messages []
                 :unsaved? true}  ; optional field should be stripped
          plan (topic/topic->save-plan topic "/test/dir")]
      (is (= {:id "topic-123"
              :name "Test Topic"
              :messages []}
             (:topic plan))))))

