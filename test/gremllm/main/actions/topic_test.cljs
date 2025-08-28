(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan"
    (let [topic {:id "topic-1234567890-abc123"
                 :messages []}
          config {:topics-dir "/test/dir"}
          plan (topic/topic->save-plan topic config)]
      (is (= {:dir "/test/dir"
              :filename "topic-1234567890-abc123.edn"
              :filepath "/test/dir/topic-1234567890-abc123.edn"
              :content "{:id \"topic-1234567890-abc123\", :messages []}"
              :topic topic}
             plan))))

  (testing "throws when topic has no ID"
    (is (thrown-with-msg? js/Error #"Topic must have an :id field"
          (topic/topic->save-plan {:messages []} {:topics-dir "/test"})))))

