(ns gremllm.main.io-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

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
  (testing "ensures parent directory exists before writing"
    (let [ensure-dir-arg      (atom nil)
          write-file-called?  (atom false)
          filepath            "/app/data/User/secrets.edn"
          expected-parent-dir (io/path-dirname filepath)]
      (with-redefs [io/ensure-dir (fn [dir]
                                    (reset! ensure-dir-arg dir))
                    io/write-file (fn [_ _]
                                    (reset! write-file-called? true))]
        (io/write-secrets-file filepath {:key "value"})
        (is (= expected-parent-dir @ensure-dir-arg))
        (is @write-file-called?))))

  (testing "writes EDN format"
    (with-redefs [io/ensure-dir (fn [_])
                  io/write-file (fn [_path content]
                                  (is (= "{:api-key \"encrypted\"}" content)))]
      (io/write-secrets-file "/path/secrets.edn" {:api-key "encrypted"}))))

(deftest test-topics-dir-path
 (let [workspace-dir "/app/data/User/workspaces/default"]
   (is (= "/app/data/User/workspaces/default/topics"
         (io/topics-dir-path workspace-dir)))))

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
