(ns gremllm.main.io-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

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
