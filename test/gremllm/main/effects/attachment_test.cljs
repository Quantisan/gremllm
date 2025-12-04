(ns gremllm.main.effects.attachment-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.attachment :as attachment]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(deftest test-store-and-load-attachment
  (testing "stores file with correct metadata and loads content back"
    (with-temp-dir "attachment-test"
      (fn [workspace-dir]
        (let [source-file (io/path-join workspace-dir "image.png")
              content "fake png content"]
          (io/write-file source-file content)
          (let [ref (attachment/store-attachment workspace-dir source-file)
                loaded (attachment/load-attachment-content workspace-dir (:ref ref))]
            ;; Correct metadata
            (is (= "image.png" (:name ref)))
            (is (= "image/png" (:mime-type ref)))
            (is (= (count content) (:size ref)))
            ;; Content loads back
            (is (= content (.toString loaded "utf8")))))))))

(deftest test-deduplication
  (testing "same content stored twice returns same ref"
    (with-temp-dir "dedup-test"
      (fn [workspace-dir]
        (let [file1 (io/path-join workspace-dir "file1.txt")
              file2 (io/path-join workspace-dir "file2.txt")]
          (io/write-file file1 "duplicate content")
          (io/write-file file2 "duplicate content")
          (let [ref1 (attachment/store-attachment workspace-dir file1)
                ref2 (attachment/store-attachment workspace-dir file2)]
            (is (= (:ref ref1) (:ref ref2)))))))))

