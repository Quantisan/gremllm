(ns gremllm.main.actions.acp-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.acp :as acp]))

(deftest text-only-message-test
  (testing "message with no :context produces single text block"
    (is (= [{:type "text" :text "hello"}]
           (acp/prompt-content-blocks {:text "hello"} nil)))))

(deftest text-only-with-document-path-test
  (is (= [{:type "text" :text "hello"}
          {:type "resource_link"
           :uri "file:///workspace/document.md"
           :name "document.md"}]
         (acp/prompt-content-blocks {:text "hello"} "/workspace/document.md"))))

(deftest same-block-excerpt-includes-text-and-label-test
  (let [excerpt {:id "e1"
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
                                       :block-text-snippet "Our Gremllm launched on a Tuesday."}}}
        message {:text "reword these"
                 :context {:excerpts [excerpt]}}
        [text-block] (acp/prompt-content-blocks message nil)
        body (:text text-block)]
    (is (= "text" (:type text-block)))
    (is (re-find #"reword these" body))
    (is (re-find #"References:" body))
    (is (re-find #"launched on a Tuesday" body))
    (is (re-find #"p2" body))
    (is (not (re-find #"offset" body)))
    (is (re-find #"Our Gremllm launched on a Tuesday\." body))))

(deftest cross-block-excerpt-no-offsets-test
  (let [excerpt {:id "e2"
                 :text "Gremllm Launch Log\nOur Gremllm"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :heading
                                         :index 1
                                         :start-line 1
                                         :end-line 1
                                         :block-text-snippet "Gremllm Launch Log"}
                           :end-block {:kind :paragraph
                                       :index 2
                                       :start-line 3
                                       :end-line 3
                                       :block-text-snippet "Our Gremllm launched on a Tuesday."}}}
        message {:text "compare these"
                 :context {:excerpts [excerpt]}}
        [text-block] (acp/prompt-content-blocks message nil)
        body (:text text-block)]
    (is (re-find #"h1 -> p2" body))
    (is (not (re-find #"offset" body)))))

(deftest excerpt-text-renders-as-blockquote-test
  (testing "excerpt text is rendered as markdown blockquote, not pr-str quoted"
    (let [excerpt {:id "e1"
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
                                         :block-text-snippet "Our Gremllm launched on a Tuesday."}}}
          message {:text "reword" :context {:excerpts [excerpt]}}
          [text-block] (acp/prompt-content-blocks message nil)
          body (:text text-block)]
      (is (re-find #"      > launched on a Tuesday" body))
      (is (not (re-find #"\"launched" body))))))

(deftest excerpt-with-document-path-appends-resource-link-test
  (let [excerpt {:id "e1"
                 :text "x"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph
                                         :index 2
                                         :start-line 3
                                         :end-line 3
                                         :block-text-snippet "x"}
                           :end-block {:kind :paragraph
                                       :index 2
                                       :start-line 3
                                       :end-line 3
                                       :block-text-snippet "x"}}}
        blocks (acp/prompt-content-blocks
                {:text "t" :context {:excerpts [excerpt]}}
                "/workspace/document.md")]
    (is (= 2 (count blocks)))
    (is (= "text" (:type (first blocks))))
    (is (= "resource_link" (:type (second blocks))))))
