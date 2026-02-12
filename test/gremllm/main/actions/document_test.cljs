(ns gremllm.main.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.document :as document-actions]
            [gremllm.main.io :as io]))

(deftest test-create-plan
  (testing "creates document plan with default content"
    (let [workspace-dir "/test/workspace"
          plan (document-actions/create-plan workspace-dir)]
      (is (= {:filepath (io/document-file-path workspace-dir)
              :content  "# Untitled Document\n"}
             plan)))))
