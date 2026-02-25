(ns gremllm.renderer.ui.document-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document :as doc-ui]
            [lookup.core :as lookup]))

(deftest render-document-test
  (testing "renders markdown content as hiccup (no innerHTML)"
    (let [hiccup (doc-ui/render-document "# Title\nSome text.")]
      (is (some? (lookup/select-one 'article hiccup)))
      (is (some? (lookup/select-one 'h1 hiccup)))
      (is (nil? (:innerHTML (lookup/attrs (lookup/select-one 'div hiccup))))
          "Should not use innerHTML")))

  (testing "renders placeholder when no content"
    (let [hiccup (doc-ui/render-document nil)]
      (is (some? (lookup/select-one 'article hiccup)))
      (is (some? (lookup/select-one 'button hiccup))))))
