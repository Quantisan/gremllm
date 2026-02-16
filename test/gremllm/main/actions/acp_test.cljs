(ns gremllm.main.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.acp :as acp]
            [gremllm.main.io]))

(deftest test-prompt-content-blocks
  (testing "text-only prompt (no document)"
    (let [blocks (acp/prompt-content-blocks "hello" nil)]
      (is (= [{:type "text" :text "hello"}]
             blocks))))

  (testing "text + document path"
    (let [blocks (acp/prompt-content-blocks "hello" "/workspace/document.md")]
        (is (= [{:type "text" :text "hello"}
                {:type "resource_link"
                 :uri  "file:///workspace/document.md"
                 :name "document.md"}]
               blocks)))))
