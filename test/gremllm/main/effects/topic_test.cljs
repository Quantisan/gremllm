(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic]))

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data"
    (let [os       (js/require "os")
          path     (js/require "path")
          fs       (js/require "fs")
          temp-dir (.join path (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.))))
          topic    {:id "123" :messages [{:role "user" :content "Hello"}]}
          filename (str "topic-" (.getTime (js/Date.)) ".edn")
          filepath (.join path temp-dir filename)]
      (try
        (let [_saved-path (topic/save {:dir temp-dir
                                       :filepath filepath
                                       :content (pr-str topic)})
              
              loaded-js (topic/load temp-dir #"topic-\d+\.edn")
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
