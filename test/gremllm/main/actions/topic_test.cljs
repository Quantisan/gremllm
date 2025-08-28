(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan"
    (let [topic {:id "topic-1234567890-abc123"
                 :messages []}
          topics-dir "/test/dir"
          filename (str (:id topic) ".edn")
          filepath (str topics-dir "/" filename)
          plan (topic/topic->save-plan topic topics-dir)]
      (is (= {:dir      topics-dir
              :filename filename
              :filepath filepath
              :content "{:id \"topic-1234567890-abc123\", :messages []}"
              :topic topic}
             plan)))))

