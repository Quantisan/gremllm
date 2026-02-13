(ns gremllm.main.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.acp :as acp-actions]))

(deftest test-prompt-content-blocks
  (testing "returns only text block when document path is nil"
    (is (= [{:type "text" :text "hello"}]
           (acp-actions/prompt-content-blocks "hello" nil))))

  (testing "returns text block and resource_link when document path exists"
    (let [document-path "/tmp/workspace/document.md"]
      (is (= [{:type "text" :text "what is in my doc?"}
              {:type "resource_link"
               :uri  "file:///tmp/workspace/document.md"
               :name "document.md"}]
             (acp-actions/prompt-content-blocks "what is in my doc?" document-path))))))
