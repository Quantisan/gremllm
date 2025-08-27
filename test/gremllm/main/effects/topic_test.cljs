(ns gremllm.main.effects.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.topic :as topic]
            [gremllm.main.actions.topic :refer [topic-file-pattern]]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(deftest test-save-load-round-trip
  (testing "save and load preserves topic data"
    (with-temp-dir "topic-save-load"
      (fn [temp-dir]
        (let [topic    {:id "topic-1754952422977-ixubncif66"
                        :name "Test Topic"
                        :messages [{:id 1754952440824 :type "user" :text "Hello"}]}
              filename (str "topic-" (.getTime (js/Date.)) ".edn")
              filepath (io/path-join temp-dir filename)
              _saved-path (topic/save {:dir temp-dir
                                       :filepath filepath
                                       :content (pr-str topic)})
              loaded-js (topic/load-latest temp-dir #"topic-\d+\.edn")
              loaded    (js->clj loaded-js :keywordize-keys true)]
          (is (= topic loaded)))))))

(deftest test-enumerate-topics
  (testing "enumerate returns only topic files, sorted, with filename and filepath"
    (with-temp-dir "list"
      (fn [dir]
        (let [files-to-create ["topic-200.edn" "notes.txt" "topic-100.edn"]
              _               (doseq [f files-to-create]
                                (io/write-file (io/path-join dir f) "{}"))]
          (let [entries (topic/enumerate dir #"topic-\d+\.edn")]
            (is (= [{:filename "topic-100.edn"
                     :filepath (io/path-join dir "topic-100.edn")}
                    {:filename "topic-200.edn"
                     :filepath (io/path-join dir "topic-200.edn")}]
                   (mapv #(select-keys % [:filename :filepath]) entries)))
            (is (every? number? (map :created-at entries)))
            (is (every? number? (map :last-accessed-at entries)))))))))

(deftest test-load-all
  (testing "load-all returns map of all topics keyed by ID"
    (with-temp-dir "load-all"
      (fn [dir]
        ;; TODO: we should create a topic schema and use that throughout, and to generate these data
        ;; here
        (let [topic1 {:id "topic-1754952422977-ixubncif66"
                      :name "Testing 2"
                      :messages [{:id 1754952440824 :type "user" :text "Hello"}]}
              topic2 {:id "topic-1754952422978-abcdef12345"
                      :name "Another Topic"
                      :messages [{:id 1754952440825 :type "assistant" :text "Hi"}]}
              ;; Save with matching filename format
              _      (io/write-file (io/path-join dir "topic-1754952422977.edn") (pr-str topic1))
              _      (io/write-file (io/path-join dir "topic-1754952422978.edn") (pr-str topic2))
              _      (io/write-file (io/path-join dir "notes.txt") "ignored file")
              result (topic/load-all dir topic-file-pattern)]
          (is (= {"1754952422977" topic1
                  "1754952422978" topic2}
                 result))))))

  (testing "returns empty map when directory doesn't exist"
    (let [result (topic/load-all "/nonexistent/dir" #"topic-\d+\.edn")]
      (is (= {} result))))

  (testing "continues loading when encountering invalid EDN"
    (with-temp-dir "load-all-invalid"
      (fn [dir]
        (let [valid-topic {:id "topic-1754952422979-xyz789"
                           :name "Valid Topic"
                           :messages []}
              _           (io/write-file (io/path-join dir "topic-1754952422979.edn") (pr-str valid-topic))
              _           (io/write-file (io/path-join dir "topic-666.edn") "{:unclosed")
              result      (topic/load-all dir #"topic-\d+\.edn")]
          (is (= {"1754952422979" valid-topic} result)))))))
