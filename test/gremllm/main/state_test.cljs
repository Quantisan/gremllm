(ns gremllm.main.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.state :as state]
            [gremllm.main.io :as io]))

(deftest test-active-document-path
  (is (= "/Users/paul/memo.md"
         (state/get-active-document-path {:active-document-path "/Users/paul/memo.md"})))
  (is (nil? (state/get-active-document-path {}))))

(deftest test-user-data-dir
  (is (= "/app/data" (state/get-user-data-dir {:user-data-dir "/app/data"})))
  (is (nil? (state/get-user-data-dir {}))))

(deftest test-get-storage-dir
  (testing "derives storage dir from user-data-dir + active-document-path"
    (let [doc-path "/Users/paul/memo.md"
          state    {:user-data-dir "/app/data" :active-document-path doc-path}]
      (is (= (io/document-storage-dir "/app/data" doc-path)
             (state/get-storage-dir state)))))
  (testing "nil when either input missing"
    (is (nil? (state/get-storage-dir {:user-data-dir "/app/data"})))
    (is (nil? (state/get-storage-dir {:active-document-path "/Users/paul/memo.md"})))
    (is (nil? (state/get-storage-dir {})))))

(deftest test-get-topics-dir
  (testing "topics dir under derived storage dir"
    (let [doc-path "/Users/paul/memo.md"
          state    {:user-data-dir "/app/data" :active-document-path doc-path}]
      (is (= (io/topics-dir-path (io/document-storage-dir "/app/data" doc-path))
             (state/get-topics-dir state)))))
  (testing "nil when storage dir cannot be derived"
    (is (nil? (state/get-topics-dir {})))))
