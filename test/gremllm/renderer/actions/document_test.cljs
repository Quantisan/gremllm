(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.renderer.state.document :as document-state]))

(deftest set-content-test
  (let [effects (document/set-content {} "# Replaced")]
    (testing "saves the new content first"
      (is (= [:effects/save document-state/content-path "# Replaced"]
             (first effects))))

    (testing "dispatches cross-topic excerpt invalidation"
      (is (some #{[:excerpt.actions/invalidate-across-topics]} effects)))

    (testing "dismisses live capture state"
      (is (some #{[:excerpt.actions/dismiss-popover]} effects)))))
