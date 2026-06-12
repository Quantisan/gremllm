(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.io :as io]
            [gremllm.schema-test :as schema-test]
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


(defn- make-valid-topic []
  {:id "topic-1754952422977-ixubncif66"
   :name "Test Topic"
   :messages [{:id 1754952440824 :type :user :text "Hello"}]
   :session {:pending-diffs []}
   :excerpts []})

(deftest anchor-round-trip-test
  (testing "anchor survives save → load through the disk codec"
    (with-temp-dir "anchor-round-trip"
      (fn [temp-dir]
        (let [topic  (assoc (make-valid-topic) :anchor schema-test/anchor-fixture)
              plan   (topic-actions/topic->save-plan topic temp-dir)
              _      (topic-effects/save-topic plan)
              loaded (get (topic-effects/load-topics temp-dir) (:id topic))]
          (is (= schema-test/anchor-fixture (:anchor loaded))))))))

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
