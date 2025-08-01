(ns gremllm.main.io-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
            [gremllm.main.io :as io]
            ["fs" :as fs]))

(deftest test-secrets-file-path
  (testing "secrets file path includes User subdirectory and secrets.edn"
    (let [user-data-dir "/app/data"
          secrets-path (io/secrets-file-path user-data-dir)]
      (is (= "/app/data/User/secrets.edn" secrets-path)))))

(deftest test-read-secrets-file
  (testing "returns empty map when file doesn't exist"
    (with-redefs [fs/existsSync (fn [_] false)]
      (is (= {} (io/read-secrets-file "/fake/path/secrets.edn")))))

  (testing "reads and parses EDN content when file exists"
    (with-redefs [fs/existsSync (fn [_] true)
                  io/read-file (fn [_] "{:api-key \"encrypted-value\"}")]
      (is (= {:api-key "encrypted-value"}
             (io/read-secrets-file "/fake/path/secrets.edn")))))

  (testing "returns empty map on parse error"
    (with-redefs [fs/existsSync (fn [_] true)
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
