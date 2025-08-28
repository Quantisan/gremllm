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
        ;; TODO: we should create a topic schema and use that throughout, and to generate these data
        ;; here
        (let [topic1 {:id "topic-1754952422977-ixubncif66"
                      :name "Testing 2"
                      :messages [{:id 1754952440824 :type "user" :text "Hello"}]}
              topic2 {:id "topic-1754952422978-abcdef12345"
                      :name "Another Topic"
                      :messages [{:id 1754952440825 :type "assistant" :text "Hi"}]}
              ;; Save with matching filename format - use full topic ID
              _      (io/write-file (io/path-join dir "topic-1754952422977-ixubncif66.edn") (pr-str topic1))
              _      (io/write-file (io/path-join dir "topic-1754952422978-abcdef12345.edn") (pr-str topic2))
              _      (io/write-file (io/path-join dir "notes.txt") "ignored file")
              result (topic/load-all dir topic-file-pattern)]
          (is (= {"topic-1754952422977-ixubncif66" topic1
                  "topic-1754952422978-abcdef12345" topic2}
                 result))))))

  (testing "returns empty map when directory doesn't exist"
    (let [result (topic/load-all "/nonexistent/dir" topic-file-pattern)]
      (is (= {} result))))

  (testing "continues loading when encountering invalid EDN"
    (with-temp-dir "load-all-invalid"
      (fn [dir]
        (let [valid-topic {:id "topic-1754952422979-xyz789"
                           :name "Valid Topic"
                           :messages []}
              _              (io/write-file (io/path-join dir "topic-1754952422979-xyz789.edn") (pr-str valid-topic))
              _              (io/write-file (io/path-join dir "topic-666-invalid123.edn") "{:unclosed")

              ;; Temporarily replace console.error with no-op
              original-error js/console.error
              _              (set! js/console.error (fn [& _args] nil))

              result         (topic/load-all dir topic-file-pattern)]
          ;; Restore original console.error
          (set! js/console.error original-error)
          (is (= {"topic-1754952422979-xyz789" valid-topic} result)))))))
