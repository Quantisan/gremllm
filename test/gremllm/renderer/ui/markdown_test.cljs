(ns gremllm.renderer.ui.markdown-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.markdown :as md]
            [lookup.core :as lookup]))

(deftest markdown->hiccup-test
  (testing "renders headings"
    (let [hiccup (md/markdown->hiccup "# Hello")]
      (is (some? (lookup/select-one 'h1 hiccup)))))

  (testing "renders bold and emphasis"
    (let [hiccup (md/markdown->hiccup "**bold** and *italic*")]
      (is (some? (lookup/select-one 'strong hiccup)))
      (is (some? (lookup/select-one 'em hiccup)))))

  (testing "renders inline code"
    (let [hiccup (md/markdown->hiccup "use `code` here")]
      (is (some? (lookup/select-one 'code hiccup)))))

  (testing "renders links"
    (let [hiccup (md/markdown->hiccup "[link](https://example.com)")]
      (is (= "https://example.com"
             (-> (lookup/select-one 'a hiccup) lookup/attrs :href)))))

  (testing "renders fenced code blocks"
    (let [hiccup (md/markdown->hiccup "```js\nconsole.log('hi')\n```")]
      (is (some? (lookup/select-one 'pre hiccup)))))

  (testing "renders lists"
    (let [hiccup (md/markdown->hiccup "- one\n- two")]
      (is (some? (lookup/select-one 'ul hiccup)))
      (is (= 2 (count (lookup/select 'li hiccup)))))))
