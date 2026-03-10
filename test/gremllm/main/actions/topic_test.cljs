(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan structure"
    (let [topic {:id "topic-123"
                 :name "Test Topic"
                 :messages []
                 :session {:pending-diffs []}}
          topics-dir "/test/dir"
          plan (topic/topic->save-plan topic topics-dir)]
      (is (= topics-dir (:dir plan)))
      (is (= "/test/dir/topic-123.edn" (:filepath plan)))
      (is (= {:id "topic-123" :name "Test Topic" :messages [] :session {:pending-diffs []}}
             (edn/read-string (:content plan))))))

  (testing "strips transient fields before saving"
    (let [topic {:id "topic-123"
                 :name "Test Topic"
                 :messages []
                 :unsaved? true   ; transient field should be stripped
                 :random-945 945} ; extra fields should be stripped too

          plan (topic/topic->save-plan topic "/test/dir")]
      (is (= {:id "topic-123"
              :name "Test Topic"
              :messages []
              :session {:pending-diffs []}}
             (edn/read-string (:content plan)))))))

