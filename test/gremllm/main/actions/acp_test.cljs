(ns gremllm.main.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.acp :as acp]))

(def ^:private excerpt
  {:id "e1"
   :text "launched on a Tuesday"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph
                           :index 2
                           :start-line 3
                           :end-line 3
                           :block-text-snippet "Our Gremllm launched on a Tuesday."}
             :end-block {:kind :paragraph
                         :index 2
                         :start-line 3
                         :end-line 3
                         :block-text-snippet "Our Gremllm launched on a Tuesday."}}})

(deftest text-only-message-test
  (testing "message with no :context produces a single text block"
    (is (= [{:type "text" :text "hello"}]
           (acp/prompt-content-blocks {:text "hello"} nil)))))

(deftest excerpt-bearing-message-test
  (let [message {:text "reword these"
                 :context {:excerpts [excerpt]}}
        blocks-without-document (acp/prompt-content-blocks message nil)
        blocks-with-document (acp/prompt-content-blocks message "/workspace/document.md")
        text-block (first blocks-without-document)
        body (:text text-block)]
    (testing "without a document path only the text block is returned"
      (is (= 1 (count blocks-without-document)))
      (is (= "text" (:type text-block)))
      (is (re-find #"reword these" body))
      (is (re-find #"References:" body))
      (is (re-find #"launched on a Tuesday" body))
      (is (re-find #"p2" body)))

    (testing "with a document path a resource_link block is appended"
      (is (= 2 (count blocks-with-document)))
      (is (= "text" (:type (first blocks-with-document))))
      (is (= {:type "resource_link"
              :uri "file:///workspace/document.md"
              :name "document.md"}
             (second blocks-with-document))))))

(deftest excerpt-rendering-format-test
  (let [message {:text "reword"
                 :context {:excerpts [excerpt]}}
        [text-block] (acp/prompt-content-blocks message nil)
        body (:text text-block)]
    (testing "excerpt text is rendered as markdown blockquote content"
      (is (re-find #"      > launched on a Tuesday" body))
      (is (not (re-find #"\"launched on a Tuesday\"" body))))

    (testing "block context is rendered in its own labeled section"
      (is (re-find #"block context:" body))
      (is (re-find #"      > Our Gremllm launched on a Tuesday\." body)))))
