(ns gremllm.main.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic]))

(deftest test-topic->save-plan
  (testing "creates correct save plan"
    (let [topic {:id "123" :messages []}
          config {:timestamp 1234567890
                  :topics-dir "/test/dir"}
          plan (topic/topic->save-plan topic config)]
      (is (= plan {:dir "/test/dir"
                   :filename "topic-1234567890.edn"
                   :filepath "/test/dir/topic-1234567890.edn"
                   :content "{:id \"123\", :messages []}"
                   :topic topic})))))

(deftest test-validate-save-plan
  (testing "accepts valid plan"
    (let [plan {:filename "topic-123.edn"}]
      (is (= plan (topic/validate-save-plan plan)))))

  (testing "rejects invalid filename"
    (is (thrown-with-msg? js/Error #"Invalid filename"
          (topic/validate-save-plan {:filename "bad-name.edn"})))))

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data"
    (let [os       (js/require "os")
          path     (js/require "path")
          fs       (js/require "fs")
          temp-dir (.join path (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.))))
          topic {:id "123" :messages [{:role "user" :content "Hello"}]}]
      (try
        (let [_filepath  (topic/save nil nil topic temp-dir)

              loaded-js (topic/load nil nil temp-dir)
              loaded    (js->clj loaded-js :keywordize-keys true)]
          (is (= topic loaded)))

        (finally
          ;; Cleanup - will run even if test fails
          (when (.existsSync fs temp-dir)
            ;; Remove any files in the directory first
            (doseq [file (.readdirSync fs temp-dir)]
              (.unlinkSync fs (.join path temp-dir file)))
            ;; Then remove the directory
            (.rmdirSync fs temp-dir)))))))
