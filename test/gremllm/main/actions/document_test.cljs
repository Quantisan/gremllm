(ns gremllm.main.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.document :as document-actions]
            [gremllm.main.io :as io]
            [gremllm.main.state :as state]))

(deftest test-open
  (testing "routes doc-path to correct storage paths and effects"
    (let [user-data-dir "/app/data"
          doc-path      "/Users/paul/memo.md"
          s             {:user-data-dir user-data-dir}
          expected-paths (io/document-paths user-data-dir doc-path)]
      (is (= [[:store.effects/save state/active-document-path doc-path]
              [:document.effects/load-and-sync expected-paths]
              [:document.effects/record-source-path expected-paths]]
             (document-actions/open s doc-path))))))
