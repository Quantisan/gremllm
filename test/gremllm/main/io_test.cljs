(ns gremllm.main.io-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
            [gremllm.main.io :as io]))

;; TODO: refactor for DRY: with-temp-dir is duplicated in gremllm.main.effects.topic-test
(defn- with-temp-dir [suffix f]
  (let [os  (js/require "os")
        dir (io/path-join (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.)) "-" suffix))]
    (try
      (io/ensure-dir dir)
      (f dir)
      (finally
        (when (io/file-exists? dir)
          (doseq [file (io/read-dir dir)]
            (io/delete-file (io/path-join dir file)))
          (io/remove-dir dir))))))

(deftest test-secrets-file-path
  (testing "secrets file path includes User subdirectory and secrets.edn"
    (let [user-data-dir "/app/data"
          secrets-path (io/secrets-file-path user-data-dir)]
      (is (= "/app/data/User/secrets.edn" secrets-path)))))

(deftest test-read-secrets-file
  (testing "returns empty map when file doesn't exist"
    (with-redefs [io/file-exists? (fn [_] false)]
      (is (= {} (io/read-secrets-file "/fake/path/secrets.edn")))))

  (testing "reads and parses EDN content when file exists"
    (with-redefs [io/file-exists? (fn [_] true)
                  io/read-file (fn [_] "{:api-key \"encrypted-value\"}")]
      (is (= {:api-key "encrypted-value"}
             (io/read-secrets-file "/fake/path/secrets.edn")))))

  (testing "returns empty map on parse error"
    (with-redefs [io/file-exists? (fn [_] true)
                  io/read-file (fn [_] "{:key")]
      (is (= {} (io/read-secrets-file "/fake/path/secrets.edn"))))))

(deftest test-write-secrets-file
  (testing "ensures User directory exists before writing"
    (let [ensure-dir-called? (atom false)
          write-file-called? (atom false)]
      (with-redefs [io/ensure-dir (fn [dir]
                                    (reset! ensure-dir-called? true)
                                    (is (clojure.string/ends-with? dir "/User")))
                    io/write-file (fn [_ _]
                                    (reset! write-file-called? true))]
        (io/write-secrets-file "/app/data/User/secrets.edn" {:key "value"})
        (is @ensure-dir-called?)
        (is @write-file-called?))))

  (testing "writes EDN format"
    (with-redefs [io/ensure-dir (fn [_])
                  io/write-file (fn [_path content]
                                  (is (= "{:api-key \"encrypted\"}" content)))]
      (io/write-secrets-file "/path/secrets.edn" {:api-key "encrypted"}))))

(deftest test-path-join
  (testing "joins multiple relative segments"
    (is (= "a/b/c" (io/path-join "a" "b" "c"))))
  (testing "joins with absolute first segment"
    (is (= "/root/folder/file.txt"
           (io/path-join "/root" "folder" "file.txt")))))

(deftest test-path-dirname
  (testing "returns parent directory for file path"
    (is (= "/a/b" (io/path-dirname "/a/b/c.txt"))))
  (testing "returns parent directory for directory path"
    (is (= "/a" (io/path-dirname "/a/b")))))

(deftest test-topics-dir-path
 (let [workspace-dir "/app/data/User/workspaces/default"]
   (is (= "/app/data/User/workspaces/default/topics"
         (io/topics-dir-path workspace-dir)))))

(deftest test-file-timestamps
  (testing "returns created-at and last-accessed-at (ms)"
    (with-temp-dir "timestamps"
      (fn [dir]
        (let [start    (.getTime (js/Date.))
              filename (str "gremllm-io-ts-" start ".txt")
              filepath (io/path-join dir filename)]
          (io/write-file filepath "hello")
          (let [{:keys [created-at last-accessed-at]} (io/file-timestamps filepath)]
            (is (number? created-at))
            (is (number? last-accessed-at))
            (is (<= start created-at))
            (is (<= start last-accessed-at))))))))
