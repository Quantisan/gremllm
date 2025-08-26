(ns gremllm.main.effects.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [gremllm.main.effects.workspace :as workspace]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))


(deftest test-ls
  (testing "returns empty vector when directory doesn't exist"
    (let [non-existent-dir "/path/that/does/not/exist/workspace"]
      (is (= [] (workspace/ls non-existent-dir)))))
  
  (testing "returns directory contents when directory exists"
    (with-temp-dir "workspace-test-"
      (fn [temp-dir]
        ;; Create some test files and directories
        (io/write-file (io/path-join temp-dir "file1.txt") "content1")
        (io/write-file (io/path-join temp-dir "file2.edn") "{:data 123}")
        (io/ensure-dir (io/path-join temp-dir "subdir"))
        
        ;; Test ls function
        (let [contents (workspace/ls temp-dir)]
          (is (vector? contents))
          (is (= 3 (count contents)))
          (is (contains? (set contents) "file1.txt"))
          (is (contains? (set contents) "file2.edn"))
          (is (contains? (set contents) "subdir")))))))
