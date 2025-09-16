(ns gremllm.main.effects.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.workspace :as workspace]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(defn- write-topic-file [dir topic]
  (let [filename (str (:id topic) ".edn")
        filepath (io/path-join dir filename)]
    (io/write-file filepath (pr-str topic))))

(defn- write-file [dir filename content]
  (io/write-file (io/path-join dir filename) content))

(deftest test-parse-topic-content
  (testing "parses valid topic content"
    (let [topic {:id "topic-123" :name "Test" :messages []}
          content (pr-str topic)
          result (#'workspace/parse-topic-content content "test.edn")]
      (is (= topic result))))

  (testing "returns nil for invalid EDN"
    ;; Suppress console.error for this test
    (let [original-error js/console.error]
      (set! js/console.error (constantly nil))
      (is (nil? (#'workspace/parse-topic-content "{:broken" "bad.edn")))
      (is (nil? (#'workspace/parse-topic-content "not-edn" "bad.edn")))
      (set! js/console.error original-error)))

  (testing "applies schema coercion"
    (let [topic-without-unsaved {:id "topic-123" :name "Test" :messages []}
          content (pr-str topic-without-unsaved)
          result (#'workspace/parse-topic-content content "test.edn")]
      ;; schema/topic-from-disk should not add :unsaved? key
      (is (nil? (:unsaved? result))))))

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data"
    (with-temp-dir "topic-save-load"
      (fn [temp-dir]
        (let [topic    {:id "topic-1754952422977-ixubncif66"
                        :name "Test Topic"
                        :messages [{:id 1754952440824 :type :user :text "Hello"}]}
              filename (str (:id topic) ".edn")
              filepath (io/path-join temp-dir filename)
              _saved-path (workspace/save-topic {:dir temp-dir
                                                 :filepath filepath
                                                 :content (pr-str topic)})
              all-topics (workspace/load-topics temp-dir)
              loaded (get all-topics (:id topic))]
          (is (= topic loaded)))))))

(deftest test-enumerate-topics
  (testing "enumerate returns only topic files, sorted, with filename and filepath"
    (with-temp-dir "list"
      (fn [dir]
        (let [files-to-create ["topic-200-bbb.edn" "notes.txt" "topic-100-aaa.edn"]
              _               (doseq [f files-to-create]
                                (io/write-file (io/path-join dir f) "{}"))]
          (let [entries (workspace/enumerate dir)]
            (is (= [{:filename "topic-100-aaa.edn"
                     :filepath (io/path-join dir "topic-100-aaa.edn")}
                    {:filename "topic-200-bbb.edn"
                     :filepath (io/path-join dir "topic-200-bbb.edn")}]
                   (mapv #(select-keys % [:filename :filepath]) entries)))
            (is (every? number? (map :created-at entries)))
            (is (every? number? (map :last-accessed-at entries)))))))))

(deftest test-load-topics
  (testing "returns empty map for non-existent directory"
    (is (= {} (workspace/load-topics "/does/not/exist"))))

  (testing "load-topics returns map of all topics keyed by ID"
    (with-temp-dir "load-topics"
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
                 (workspace/load-topics dir)))))))

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
            (let [result (workspace/load-topics dir)]
              ;; Restore original console.error
              (set! js/console.error original-error)
              (is (= {(:id good-topic) good-topic} result)))))))))

(deftest test-load-and-sync
  (testing "loads topics and dispatches sync event"
    (with-temp-dir "load-sync"
      (fn [temp-dir]
        (let [topics-dir (io/topics-dir-path temp-dir)
              topic {:id "topic-123" :name "Test" :messages []}
              dispatched (atom nil)]

          ;; Setup: write a topic file
          (io/ensure-dir topics-dir)
          (write-topic-file topics-dir topic)

          ;; Execute with mock context
          (workspace/load-and-sync
            {:dispatch #(reset! dispatched %)}
            nil
            temp-dir)

          ;; Verify IPC effect
          (let [[[effect-key channel data]] @dispatched]
            (is (= :ipc.effects/send-to-renderer effect-key))
            (is (= "workspace:sync" channel))
            (is (contains? data :topics))))))))
