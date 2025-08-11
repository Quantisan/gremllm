(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic]
            [gremllm.main.io :as io]))

(defn- with-temp-dir [suffix f]
  (let [os  (js/require "os")
        dir (io/path-join (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.)) "-" suffix))]
    (try
      (io/ensure-dir dir)
      (f dir)
      (finally
        (when (io/file-exists? dir)
          (doseq [file (io/read-dir dir)]
            (io/delete-file (io/path-join dir file)))
          (io/remove-dir dir))))))

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
    (with-temp-dir "list"
      (fn [dir]
        (doseq [f ["topic-100.edn" "topic-200.edn" "notes.txt"]]
          (io/write-file (io/path-join dir f) "{}"))
        (is (= (mapv (fn [f] {:filename f :filepath (io/path-join dir f)})
                     ["topic-100.edn" "topic-200.edn"])
               (topic/enumerate dir #"topic-\d+\.edn")))))))
