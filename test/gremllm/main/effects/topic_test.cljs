(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic]
            [gremllm.main.io :as io]))

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
              (.unlinkSync fs (io/path-join temp-dir file)))
            ;; Then remove the directory
            (.rmdirSync fs temp-dir)))))))

(deftest test-enumerate-topics
  (testing "lists only topic files sorted and returns filename + filepath (CLJ data)"
    (let [os       (js/require "os")
          fs       (js/require "fs")
          temp-dir (io/path-join (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.)) "-list"))
          f1       "topic-100.edn"
          f2       "topic-200.edn"
          other    "notes.txt"
          p1       (io/path-join temp-dir f1)
          p2       (io/path-join temp-dir f2)
          p3       (io/path-join temp-dir other)]
      (try
        (.mkdirSync fs temp-dir #js {:recursive true})
        (.writeFileSync fs p1 "{}" "utf8")
        (.writeFileSync fs p2 "{}" "utf8")
        (.writeFileSync fs p3 "{}" "utf8")
        (let [listed (topic/enumerate temp-dir #"topic-\d+\.edn")]
          (is (= [{:filename f1 :filepath p1}
                  {:filename f2 :filepath p2}]
                 listed)))
        (finally
          (when (.existsSync fs temp-dir)
            (doseq [file (.readdirSync fs temp-dir)]
              (.unlinkSync fs (io/path-join temp-dir file)))
            (.rmdirSync fs temp-dir)))))))
