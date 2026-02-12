(ns gremllm.main.effects.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.workspace :as workspace]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir with-console-error-silenced]]))

(defn- write-topic-file [dir topic]
  (let [filename (str (:id topic) ".edn")
        filepath (io/path-join dir filename)]
    (io/write-file filepath (pr-str topic))))

(defn- write-file [dir filename content]
  (io/write-file (io/path-join dir filename) content))

;; TODO: DRY: use schema/PersistedTopic default instead of hardcoding instances

(deftest test-parse-topic-content
  (testing "parses valid topic content"
    (let [topic {:id "topic-123" :name "Test" :model "claude-sonnet-4-5-20250929" :reasoning? true :messages []}
          content (pr-str topic)
          result (#'workspace/parse-topic-content content "test.edn")]
      (is (= topic result))))

  (testing "returns nil for invalid EDN"
    (with-console-error-silenced
      (is (nil? (#'workspace/parse-topic-content "{:broken" "bad.edn")))
      (is (nil? (#'workspace/parse-topic-content "not-edn" "bad.edn")))))

  (testing "applies schema coercion"
    (let [topic-without-unsaved {:id "topic-123" :name "Test" :model "claude-sonnet-4-5-20250929" :reasoning? true :messages []}
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
                        :model "claude-sonnet-4-5-20250929"
                        :reasoning? true
                        :messages [{:id 1754952440824 :type :user :text "Hello"}]}
              filename (str (:id topic) ".edn")
              filepath (io/path-join temp-dir filename)
              _saved-path (workspace/save-topic {:dir temp-dir
                                                 :filepath filepath
                                                 :content (pr-str topic)})
              all-topics (workspace/load-topics temp-dir)
              loaded (get all-topics (:id topic))]
          (is (= topic loaded)))))))

(deftest test-create-document
  (testing "creates document file and returns content"
    (with-temp-dir "create-document"
      (fn [temp-dir]
        (let [content  "# Untitled Document\n"
              filepath (io/document-file-path temp-dir)
              result   (workspace/create-document {:filepath filepath :content content})]
          (is (= content (.-content result)))
          (is (= content (io/read-file filepath))))))))

(deftest test-enumerate-topics
  (testing "enumerate returns only topic files, sorted, with filename and filepath"
    (with-temp-dir "list"
      (fn [dir]
        (let [files-to-create ["topic-200-bbb.edn" "notes.txt" "topic-100-aaa.edn"]
              _               (doseq [f files-to-create]
                                (io/write-file (io/path-join dir f) "{}"))
              entries         (workspace/enumerate dir)]
          (is (= [{:filename "topic-100-aaa.edn"
                    :filepath (io/path-join dir "topic-100-aaa.edn")}
                  {:filename "topic-200-bbb.edn"
                    :filepath (io/path-join dir "topic-200-bbb.edn")}]
                 (mapv #(select-keys % [:filename :filepath]) entries)))
          (is (every? number? (map :created-at entries)))
          (is (every? number? (map :last-accessed-at entries))))))))

(deftest test-load-topics
  (testing "returns empty map for non-existent directory"
    (is (= {} (workspace/load-topics "/does/not/exist"))))

  (testing "load-topics returns map of all topics keyed by ID"
    (with-temp-dir "load-topics"
      (fn [dir]
        ;; Simple test topics with just the essentials
        (let [topic-1 {:id "topic-1-a" :name "First" :model "claude-sonnet-4-5-20250929" :reasoning? true :messages []}
              topic-2 {:id "topic-2-b" :name "Second" :model "claude-sonnet-4-5-20250929" :reasoning? false :messages []}]

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
        (let [good-topic {:id "topic-111-good" :name "Valid" :model "claude-sonnet-4-5-20250929" :reasoning? true :messages []}]
          ;; Write one valid and one corrupt file
          (write-topic-file dir good-topic)
          (write-file dir "topic-999-bad.edn" "{:broken")

          ;; Should load only the valid topic, ignoring corrupt one
          (with-console-error-silenced
            (let [result (workspace/load-topics dir)]
              (is (= {(:id good-topic) good-topic} result)))))))))

(deftest test-load-and-sync
  (testing "loads topics and dispatches sync event"
    (with-temp-dir "load-sync"
      (fn [temp-dir]
        (let [topics-dir (io/topics-dir-path temp-dir)
              topic {:id "topic-123" :name "Test" :model "claude-sonnet-4-5-20250929" :reasoning? true :messages []}
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
            (is (= "workspace:opened" channel))
            (is (contains? data :topics))
            (is (contains? data :document))))))))
