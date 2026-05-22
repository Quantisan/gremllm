(ns gremllm.main.io-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(deftest test-topics-dir-path
 (let [document-data-dir "/app/data/User/documents/abc123"]
   (is (= "/app/data/User/documents/abc123/topics"
         (io/topics-dir-path document-data-dir)))))

(deftest test-path->document-hash
  (testing "hex-encoded, fixed length (SHA-256 = 64 chars)"
    (let [hash (io/path->document-hash "/Users/paul/memo.md")]
      (is (string? hash))
      (is (= 64 (count hash)))
      (is (re-matches #"[0-9a-f]+" hash))))
  (testing "stable: same path yields same hash"
    (is (= (io/path->document-hash "/Users/paul/memo.md")
           (io/path->document-hash "/Users/paul/memo.md"))))
  (testing "different paths yield different hashes"
    (is (not= (io/path->document-hash "/Users/paul/a.md")
              (io/path->document-hash "/Users/paul/b.md"))))
  (testing "normalizes path: equivalent paths yield same hash"
    (is (= (io/path->document-hash "/Users/paul/memo.md")
           (io/path->document-hash "/Users/paul/../paul/memo.md")))))

(deftest test-document-data-dir
  (testing "composes <user-data>/User/documents/<hash>"
    (let [doc-path "/Users/paul/memo.md"
          hash     (io/path->document-hash doc-path)]
      (is (= (str "/app/data/User/documents/" hash)
             (io/document-data-dir "/app/data" doc-path))))))

(deftest test-file-timestamps
  (testing "returns created-at and last-accessed-at (ms)"
    (with-temp-dir "timestamps"
      (fn [dir]
        (let [now      (.getTime (js/Date.))
              filename (str "gremllm-io-ts-" now ".txt")
              filepath (io/path-join dir filename)]
          (io/write-file filepath "hello")
          (let [{:keys [created-at last-accessed-at]} (io/file-timestamps filepath)]
            (is (number? created-at))
            (is (number? last-accessed-at))
            ;; Use tolerance - js/Date and filesystem clocks can skew in CI
            (is (< (js/Math.abs (- created-at now)) 1000))
            (is (< (js/Math.abs (- last-accessed-at now)) 1000))))))))
