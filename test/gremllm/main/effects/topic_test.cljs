(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic]
            [gremllm.main.actions.topic :refer [topic-file-pattern]]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(defn- make-test-topic [id name messages]
  {:id id
   :name name
   :messages messages})

(defn- write-topic-file [dir topic]
  (let [filename (str (:id topic) ".edn")
        filepath (io/path-join dir filename)]
    (io/write-file filepath (pr-str topic))))

(defn- write-file [dir filename content]
  (io/write-file (io/path-join dir filename) content))

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data"
    (with-temp-dir "topic-save-load"
      (fn [temp-dir]
        (let [topic    {:id "topic-1754952422977-ixubncif66"
                        :name "Test Topic"
                        :messages [{:id 1754952440824 :type "user" :text "Hello"}]}
              filename (str (:id topic) ".edn")
              filepath (io/path-join temp-dir filename)
              _saved-path (topic/save {:dir temp-dir
                                       :filepath filepath
                                       :content (pr-str topic)})
              loaded-js (topic/load-latest temp-dir topic-file-pattern)
              loaded    (js->clj loaded-js :keywordize-keys true)]
          (is (= topic loaded)))))))

(deftest test-enumerate-topics
  (testing "enumerate returns only topic files, sorted, with filename and filepath"
    (with-temp-dir "list"
      (fn [dir]
        (let [files-to-create ["topic-200-bbb.edn" "notes.txt" "topic-100-aaa.edn"]
              _               (doseq [f files-to-create]
                                (io/write-file (io/path-join dir f) "{}"))]
          (let [entries (topic/enumerate dir topic-file-pattern)]
            (is (= [{:filename "topic-100-aaa.edn"
                     :filepath (io/path-join dir "topic-100-aaa.edn")}
                    {:filename "topic-200-bbb.edn"
                     :filepath (io/path-join dir "topic-200-bbb.edn")}]
                   (mapv #(select-keys % [:filename :filepath]) entries)))
            (is (every? number? (map :created-at entries)))
            (is (every? number? (map :last-accessed-at entries)))))))))

(deftest test-load-all
  (testing "load-all returns map of all topics keyed by ID"
    (with-temp-dir "load-all"
      (fn [dir]
        ;; Simple test topics with just the essentials
        (let [topic-1 {:id "topic-1-a" :name "First" :messages []}
              topic-2 {:id "topic-2-b" :name "Second" :messages []}]

          ;; Write valid topic files
          (write-topic-file dir topic-1)
          (write-topic-file dir topic-2)

          ;; Write non-topic file (should be ignored)
          (write-file dir "readme.txt" "ignored")

          ;; Verify we get both topics back as a map
          (is (= {"topic-1-a" topic-1
                  "topic-2-b" topic-2}
                 (topic/load-all dir topic-file-pattern)))))))

  (testing "returns empty map for non-existent directory"
    (is (= {} (topic/load-all "/does/not/exist" topic-file-pattern))))

  (testing "skips corrupt files and loads valid ones"
    (with-temp-dir "load-with-corrupt"
      (fn [dir]
        (let [good-topic {:id "topic-111-good" :name "Valid" :messages []}]
          ;; Write one valid and one corrupt file
          (write-topic-file dir good-topic)
          (write-file dir "topic-999-bad.edn" "{:broken")

          ;; Should load only the valid topic, ignoring corrupt one
          (let [original-error js/console.error]
            ;; Temporarily suppress console.error
            (set! js/console.error (constantly nil))
            (let [result (topic/load-all dir topic-file-pattern)]
              ;; Restore original console.error
              (set! js/console.error original-error)
              (is (= {(:id good-topic) good-topic} result)))))))))
