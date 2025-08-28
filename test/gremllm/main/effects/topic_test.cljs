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
        (let [topics [(make-test-topic "topic-1754952422977-ixubncif66"
                                       "Testing 2"
                                       [{:id 1754952440824 :type "user" :text "Hello"}])
                      (make-test-topic "topic-1754952422978-abcdef12345"
                                       "Another Topic"
                                       [{:id 1754952440825 :type "assistant" :text "Hi"}])]
              expected-map (into {} (map (juxt :id identity)) topics)]
          
          ;; Write topic files
          (doseq [topic topics]
            (write-topic-file dir topic))
          
          ;; Write a non-topic file to ensure it's ignored
          (write-file dir "notes.txt" "ignored file")
          
          (is (= expected-map (topic/load-all dir topic-file-pattern)))))))

  (testing "returns empty map when directory doesn't exist"
    (is (= {} (topic/load-all "/nonexistent/dir" topic-file-pattern))))

  (testing "continues loading when encountering invalid EDN"
    (with-temp-dir "load-all-invalid"
      (fn [dir]
        (let [valid-topic (make-test-topic "topic-1754952422979-xyz789"
                                          "Valid Topic"
                                          [])]
          (write-topic-file dir valid-topic)
          (write-file dir "topic-666-invalid123.edn" "{:unclosed")
          
          ;; Suppress console.error during test
          (with-redefs [js/console.error (constantly nil)]
            (is (= {(:id valid-topic) valid-topic}
                   (topic/load-all dir topic-file-pattern)))))))))
