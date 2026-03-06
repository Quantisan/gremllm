(ns gremllm.renderer.ui.document-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document :as doc-ui]
            [lookup.core :as lookup]))

(deftest render-document-test
  (testing "with pending diffs switches to diff mode"
    ;; Real data from list_diff.log
    (let [content "# Requirements\n\n- Support 100 concurrent users\n- Maintain data consistency across replicas\n- Provide an audit log for all writes\n- Run on hardware with 4GB RAM minimum\n\nDeployment includes monitoring integration."
          diffs   [{:type     "diff"
                    :old-text "- Support 100 concurrent users"
                    :new-text "- Support 500 concurrent users"}]
          hiccup  (doc-ui/render-document content diffs)]
      (is (some? (lookup/select-one '.diff-mode hiccup)))
      (is (some? (lookup/select-one 'del hiccup)))
      (is (some? (lookup/select-one 'ins hiccup)))))

  (testing "without diffs renders normal markdown (no diff-mode class)"
    (let [hiccup (doc-ui/render-document "# Title\n\nText." [])]
      (is (nil?   (lookup/select-one '.diff-mode hiccup)))
      (is (some? (lookup/select-one 'h1 hiccup)))))

  (testing "nil diffs renders normal markdown"
    (let [hiccup (doc-ui/render-document "# Title\n\nText." nil)]
      (is (nil? (lookup/select-one '.diff-mode hiccup)))))

  (testing "nil content renders empty state with create button"
    (let [hiccup (doc-ui/render-document nil [])]
      (is (some? (lookup/select-one 'button hiccup))))))
