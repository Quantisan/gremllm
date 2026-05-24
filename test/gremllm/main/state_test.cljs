(ns gremllm.main.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.state :as state]
            [gremllm.main.io :as io]))

(deftest test-get-document-paths
  (testing "derives document paths from user-data-dir + active-document-path"
    (let [doc-path "/Users/paul/memo.md"
          state    {:user-data-dir "/app/data" :active-document-path doc-path}
          paths    (state/get-document-paths state)]
      (is (= (io/document-paths "/app/data" doc-path) paths))))
  (testing "nil when either input missing"
    (is (nil? (state/get-document-paths {:user-data-dir "/app/data"})))
    (is (nil? (state/get-document-paths {:active-document-path "/Users/paul/memo.md"})))
    (is (nil? (state/get-document-paths {})))))
