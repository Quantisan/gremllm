(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir with-console-error-silenced]]))

(defn- write-topic-file [dir topic]
  (let [filename (str (:id topic) ".edn")
        filepath (io/path-join dir filename)]
    (io/write-file filepath (pr-str topic))))

(defn- write-file [dir filename content]
  (io/write-file (io/path-join dir filename) content))

;; TODO: DRY: use schema/PersistedTopic default instead of hardcoding instances

(def ^:private excerpt-fixture
  {:id "staged-1"
   :text "Our Gremllm crew"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph
                           :index 2
                           :start-line 3
                           :end-line 3
                           :block-text-snippet "Our Gremllm crew tuned the launch checklist."}
             :end-block {:kind :paragraph
                         :index 2
                         :start-line 3
                         :end-line 3
                         :block-text-snippet "Our Gremllm crew tuned the launch checklist."}}})

(deftest test-parse-topic-content
  (testing "parses valid topic content"
    (let [topic {:id "topic-123" :name "Test" :messages [] :session {:pending-diffs []} :excerpts []}
          content (pr-str topic)
          result (#'topic-effects/parse-topic-content content "test.edn")]
      (is (= topic result))))

  (testing "returns nil for invalid EDN"
    (with-console-error-silenced
      (is (nil? (#'topic-effects/parse-topic-content "{:broken" "bad.edn")))
      (is (nil? (#'topic-effects/parse-topic-content "not-edn" "bad.edn")))))

)

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data including excerpts"
    (with-temp-dir "topic-save-load"
      (fn [temp-dir]
        (let [topic    {:id "topic-1754952422977-ixubncif66"
                        :name "Test Topic"
                        :messages [{:id 1754952440824 :type :user :text "Hello"}]
                        :session {:pending-diffs []}
                        :excerpts [excerpt-fixture]}
              filename (str (:id topic) ".edn")
              filepath (io/path-join temp-dir filename)
              _saved-path (topic-effects/save-topic {:dir temp-dir
                                                 :filepath filepath
                                                 :content (pr-str topic)})
              all-topics (topic-effects/load-topics temp-dir)
              loaded (get all-topics (:id topic))]
          (is (= topic loaded)))))))

(deftest test-enumerate-topics
  (testing "enumerate returns only topic files, sorted, with filename and filepath"
    (with-temp-dir "list"
      (fn [dir]
        (let [files-to-create ["topic-200-bbb.edn" "notes.txt" "topic-100-aaa.edn"]
              _               (doseq [f files-to-create]
                                (io/write-file (io/path-join dir f) "{}"))
              entries         (topic-effects/enumerate dir)]
          (is (= [{:filename "topic-100-aaa.edn"
                    :filepath (io/path-join dir "topic-100-aaa.edn")}
                  {:filename "topic-200-bbb.edn"
                    :filepath (io/path-join dir "topic-200-bbb.edn")}]
                 (mapv #(select-keys % [:filename :filepath]) entries)))
          (is (every? number? (map :created-at entries)))
          (is (every? number? (map :last-accessed-at entries))))))))

(deftest test-load-topics
  (testing "returns empty map for non-existent directory"
    (is (= {} (topic-effects/load-topics "/does/not/exist"))))

  (testing "load-topics returns map of all topics keyed by ID"
    (with-temp-dir "load-topics"
      (fn [dir]
        ;; Simple test topics with just the essentials
        (let [topic-1 {:id "topic-1-a" :name "First" :messages [] :session {:pending-diffs []} :excerpts []}
              topic-2 {:id "topic-2-b" :name "Second" :messages [] :session {:pending-diffs []} :excerpts []}]

          ;; Write valid topic files
          (write-topic-file dir topic-1)
          (write-topic-file dir topic-2)

          ;; Write non-topic file (should be ignored)
          (write-file dir "readme.txt" "ignored")

          ;; Verify we get both topics back as a map
          (is (= {"topic-1-a" topic-1
                  "topic-2-b" topic-2}
                 (topic-effects/load-topics dir)))))))

  (testing "skips corrupt files and loads valid ones"
    (with-temp-dir "load-with-corrupt"
      (fn [dir]
        (let [good-topic {:id "topic-111-good" :name "Valid" :messages [] :session {:pending-diffs []} :excerpts []}]
          ;; Write one valid and one corrupt file
          (write-topic-file dir good-topic)
          (write-file dir "topic-999-bad.edn" "{:broken")

          ;; Should load only the valid topic, ignoring corrupt one
          (with-console-error-silenced
            (let [result (topic-effects/load-topics dir)]
              (is (= {(:id good-topic) good-topic} result)))))))))
