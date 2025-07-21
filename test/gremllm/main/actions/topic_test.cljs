(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan"
    (let [uuid  (random-uuid)
          topic {:id uuid
                 :messages []}
          config {:timestamp 1234567890
                  :topics-dir "/test/dir"}
          plan (topic/topic->save-plan topic config)]
      (is (= {:dir "/test/dir"
              :filename "topic-1234567890.edn"
              :filepath "/test/dir/topic-1234567890.edn"
              :content (str "{:id #uuid \"" uuid "\", :messages []}")
              :topic topic}
             plan)))))

(deftest test-validate-save-plan
  (testing "accepts valid plan"
    (let [plan {:filename "topic-123.edn"}]
      (is (= plan (topic/validate-save-plan plan)))))

  (testing "rejects invalid filename"
    (is (thrown-with-msg? js/Error #"Invalid filename"
          (topic/validate-save-plan {:filename "bad-name.edn"})))))
