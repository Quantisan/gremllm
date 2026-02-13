(ns gremllm.main.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.io :as io]))

(deftest test-prompt-content-blocks
  (testing "returns only text block when document path is nil"
    (is (= [{:type "text" :text "hello"}]
           (acp-actions/prompt-content-blocks "hello" nil))))

  (testing "returns text block and resource_link when document path exists"
    (let [document-path "/tmp/workspace/document.md"]
      (is (= [{:type "text" :text "what is in my doc?"}
              {:type "resource_link"
               :uri  (io/path->file-uri document-path)
               :name "document.md"}]
             (acp-actions/prompt-content-blocks "what is in my doc?" document-path)))))

  (testing "encodes spaces and unicode in resource_link URI"
    (let [document-path "/tmp/my notes naïve.md"]
      (is (= [{:type "text" :text "summarize this doc"}
              {:type "resource_link"
               :uri  (io/path->file-uri document-path)
               :name "my notes naïve.md"}]
             (acp-actions/prompt-content-blocks "summarize this doc" document-path)))))

  (testing "uses document basename as resource_link name"
    (let [document-path "/tmp/workspace/notes/final-brief-v2.md"]
      (is (= [{:type "text" :text "summarize this doc"}
              {:type "resource_link"
               :uri  (io/path->file-uri document-path)
               :name "final-brief-v2.md"}]
             (acp-actions/prompt-content-blocks "summarize this doc" document-path))))))
