(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema-test :as schema-test]))

;; Anchor context fixture matching AnchorContext schema
(def anchor-context
  {:panel-rect {:top 100 :left 50 :width 800 :height 600}
   :panel-scroll-top 20})

;; Composite input that the :event/text-selection placeholder now produces
(def composite-selection
  {:selection schema-test/single-word-selection
   :anchor anchor-context})

;; ========================================
;; capture
;; ========================================

(deftest capture-test
  (testing "nil composite - dispatches dismiss-popover"
    (let [result (excerpt/capture {} nil)]
      (is (= [[:excerpt.actions/dismiss-popover]] result))))

  (testing "valid composite - saves selection at captured-path and anchor at anchor-path"
    (let [result (excerpt/capture {} composite-selection)]
      (is (= 2 (count result)))
      (is (= [:effects/save excerpt-state/captured-path schema-test/single-word-selection]
             (first result)))
      (is (= [:effects/save excerpt-state/anchor-path anchor-context]
             (second result))))))

;; ========================================
;; dismiss-popover
;; ========================================

(deftest dismiss-popover-test
  (testing "clears captured-path and anchor-path"
    (is (= [[:effects/save excerpt-state/captured-path nil]
            [:effects/save excerpt-state/anchor-path nil]]
           (excerpt/dismiss-popover {})))))
