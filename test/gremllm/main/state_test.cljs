(ns gremllm.main.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.state :as state]))

(deftest test-get-document-paths-nil-guards
  (testing "nil when either input missing"
    (is (nil? (state/get-document-paths {:user-data-dir "/app/data"})))
    (is (nil? (state/get-document-paths {:active-document-path "/Users/paul/memo.md"})))
    (is (nil? (state/get-document-paths {})))))
