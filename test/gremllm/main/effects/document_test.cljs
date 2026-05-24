(ns gremllm.main.effects.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [gremllm.main.effects.document :as document-effects]
            [gremllm.main.io :as io]
            [gremllm.test-utils :refer [with-temp-dir]]))

(defn- write-topic-file [dir topic]
  (let [filename (str (:id topic) ".edn")
        filepath (io/path-join dir filename)]
    (io/write-file filepath (pr-str topic))))

(deftest test-write-meta-if-missing!
  (testing "writes meta.edn with :doc-path when absent"
    (with-temp-dir "meta-write"
      (fn [document-data-dir]
        (let [doc-path  "/Users/paul/memo.md"
              meta-path (io/path-join document-data-dir "meta.edn")]
          (document-effects/write-meta-if-missing! document-data-dir doc-path)
          (is (io/file-exists? meta-path))
          (is (= {:doc-path doc-path}
                 (edn/read-string (io/read-file meta-path))))))))
  (testing "does not overwrite an existing meta.edn"
    (with-temp-dir "meta-keep"
      (fn [document-data-dir]
        (let [meta-path (io/path-join document-data-dir "meta.edn")]
          (io/ensure-dir document-data-dir)
          (io/write-file meta-path (pr-str {:doc-path "/original.md"}))
          (document-effects/write-meta-if-missing! document-data-dir "/different.md")
          (is (= {:doc-path "/original.md"}
                 (edn/read-string (io/read-file meta-path)))))))))

(deftest test-load-and-sync
  (testing "reads document, loads topics from document data dir, dispatches document:opened"
    (with-temp-dir "load-sync"
      (fn [temp-dir]
        (let [doc-path    (io/path-join temp-dir "memo.md")
              paths      (io/document-paths temp-dir doc-path)
              topic      {:id "topic-123" :name "Test" :messages []}
              dispatched (atom nil)]

          ;; Setup: document content on disk + a topic in the per-document data dir
          (io/write-file doc-path "# Memo\n")
          (io/ensure-dir (:topics-dir paths))
          (write-topic-file (:topics-dir paths) topic)

          ;; Execute: paths map passed as arg (action computes, effect receives)
          (document-effects/load-and-sync
            {:dispatch #(reset! dispatched %)}
            (atom {})
            paths)

          ;; Verify IPC effect
          (let [[[effect-key channel data]] @dispatched]
            (is (= :ipc.effects/send-to-renderer effect-key))
            (is (= "document:opened" channel))
            (is (= "# Memo\n" (get-in data [:document :content])))
            (is (contains? (:topics data) "topic-123")))

          ;; meta.edn NOT written by load-and-sync (separate persist-meta effect)
          (is (not (io/file-exists? (io/path-join (:data-dir paths) "meta.edn")))))))))
