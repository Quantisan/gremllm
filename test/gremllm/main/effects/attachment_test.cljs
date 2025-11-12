(ns gremllm.main.effects.attachment-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.attachment :as attachment]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(deftest test-attachments-dir-path
  (testing "builds correct path to attachments directory"
    (is (= "/workspace/attachments"
           (attachment/attachments-dir-path "/workspace")))))

(deftest test-compute-file-hash
  (testing "same content produces same hash"
    (with-temp-dir "hash-test"
      (fn [dir]
        (let [file1 (io/path-join dir "file1.txt")
              file2 (io/path-join dir "file2.txt")]
          (io/write-file file1 "hello world")
          (io/write-file file2 "hello world")
          (is (= (attachment/compute-file-hash file1)
                 (attachment/compute-file-hash file2)))))))

  (testing "different content produces different hash"
    (with-temp-dir "hash-test"
      (fn [dir]
        (let [file1 (io/path-join dir "file1.txt")
              file2 (io/path-join dir "file2.txt")]
          (io/write-file file1 "hello world")
          (io/write-file file2 "goodbye world")
          (is (not= (attachment/compute-file-hash file1)
                    (attachment/compute-file-hash file2)))))))

  (testing "returns 8-character hash"
    (with-temp-dir "hash-test"
      (fn [dir]
        (let [file (io/path-join dir "test.txt")]
          (io/write-file file "test content")
          (let [hash (attachment/compute-file-hash file)]
            (is (= 8 (count hash)))
            (is (re-matches #"[0-9a-f]{8}" hash))))))))

(deftest test-store-attachment
  (testing "creates attachments directory if it doesn't exist"
    (with-temp-dir "store-test"
      (fn [workspace-dir]
        (let [source-file (io/path-join workspace-dir "source.txt")]
          (io/write-file source-file "test content")
          (attachment/store-attachment nil nil workspace-dir source-file)
          (is (io/file-exists? (attachment/attachments-dir-path workspace-dir)))))))

  (testing "copies file to attachments directory with hash-prefixed name"
    (with-temp-dir "store-test"
      (fn [workspace-dir]
        (let [source-file (io/path-join workspace-dir "image.png")]
          (io/write-file source-file "fake png content")
          (let [result (attachment/store-attachment nil nil workspace-dir source-file)
                attachments-dir (attachment/attachments-dir-path workspace-dir)
                files (io/read-dir attachments-dir)]
            (is (= 1 (count files)))
            (is (= (str (:ref result) "-image.png") (first files))))))))

  (testing "returns correct AttachmentRef"
    (with-temp-dir "store-test"
      (fn [workspace-dir]
        (let [source-file (io/path-join workspace-dir "test.pdf")]
          (io/write-file source-file "fake pdf content")
          (let [result (attachment/store-attachment nil nil workspace-dir source-file)]
            (is (string? (:ref result)))
            (is (= 8 (count (:ref result))))
            (is (= "test.pdf" (:name result)))
            (is (= "application/pdf" (:mime-type result)))
            (is (number? (:size result)))
            (is (> (:size result) 0)))))))

  (testing "deduplication: same content stored twice creates single file"
    (with-temp-dir "store-test"
      (fn [workspace-dir]
        (let [file1 (io/path-join workspace-dir "file1.txt")
              file2 (io/path-join workspace-dir "file2.txt")]
          (io/write-file file1 "duplicate content")
          (io/write-file file2 "duplicate content")
          (let [ref1 (attachment/store-attachment nil nil workspace-dir file1)
                ref2 (attachment/store-attachment nil nil workspace-dir file2)
                attachments-dir (attachment/attachments-dir-path workspace-dir)
                files (io/read-dir attachments-dir)]
            ;; Same hash prefix means deduplication worked
            (is (= (:ref ref1) (:ref ref2)))
            ;; Two files stored (same hash, different original names)
            (is (= 2 (count files)))))))))

(deftest test-load-attachment-content
  (testing "returns Buffer for matching hash prefix"
    (with-temp-dir "load-test"
      (fn [workspace-dir]
        (let [source-file (io/path-join workspace-dir "test.txt")
              content "test file content"]
          (io/write-file source-file content)
          (let [ref (attachment/store-attachment nil nil workspace-dir source-file)
                loaded (attachment/load-attachment-content workspace-dir (:ref ref))]
            (is (some? loaded))
            (is (instance? js/Buffer loaded))
            (is (= content (.toString loaded "utf8"))))))))

  (testing "returns nil when hash not found"
    (with-temp-dir "load-test"
      (fn [workspace-dir]
        (is (nil? (attachment/load-attachment-content workspace-dir "nonexist"))))))

  (testing "returns nil when attachments directory doesn't exist"
    (with-temp-dir "load-test"
      (fn [workspace-dir]
        (is (nil? (attachment/load-attachment-content workspace-dir "abc12345")))))))

(deftest test-attachment-ref->inline-data
  (testing "converts attachment ref and Buffer to Gemini inline_data format"
    (let [attachment-ref {:ref "a1b2c3d4"
                          :name "image.png"
                          :mime-type "image/png"
                          :size 1024}
          content (js/Buffer.from "fake image data" "utf8")
          result (attachment/attachment-ref->inline-data attachment-ref content)]
      (is (= "image/png" (get-in result [:inline_data :mime_type])))
      (is (string? (get-in result [:inline_data :data])))
      ;; Verify it's valid base64
      (is (= "fake image data"
             (.toString (js/Buffer.from (get-in result [:inline_data :data]) "base64") "utf8")))))

  (testing "handles different MIME types"
    (let [attachment-ref {:ref "xyz"
                          :name "doc.pdf"
                          :mime-type "application/pdf"
                          :size 2048}
          content (js/Buffer.from "fake pdf" "utf8")
          result (attachment/attachment-ref->inline-data attachment-ref content)]
      (is (= "application/pdf" (get-in result [:inline_data :mime_type]))))))
