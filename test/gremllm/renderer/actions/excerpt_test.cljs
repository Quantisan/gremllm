(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema-test :as schema-test]))

;; Anchor context fixture matching AnchorContext schema
(def anchor-context
  {:panel-rect {:top 100 :left 50 :width 800 :height 600}
   :panel-scroll-top 20})

(def locator-debug
  {:block-kind :paragraph
   :block-index 2
   :block-start-line 3
   :block-end-line 3
   :start-offset 5
   :end-offset 14})

;; Composite input that the :event/text-selection placeholder produces,
;; with both sides already coerced at the codec boundary.
(def composite-selection
  {:selection schema-test/single-word-selection
   :anchor anchor-context
   :locator-debug locator-debug})

;; ========================================
;; capture
;; ========================================

(deftest capture-test
  (testing "nil composite - dispatches dismiss-popover"
    (let [result (excerpt/capture {} nil)]
      (is (= [[:excerpt.actions/dismiss-popover]] result))))

  (testing "valid composite saves selection, anchor, and locator debug"
    (let [result (excerpt/capture {} composite-selection)]
      (is (= [:effects/save excerpt-state/captured-path schema-test/single-word-selection]
             (nth result 0)))
      (is (= [:effects/save excerpt-state/anchor-path anchor-context]
             (nth result 1)))
      (is (= [:effects/save excerpt-state/locator-debug-path locator-debug]
             (nth result 2))))))

;; ========================================
;; dismiss-popover
;; ========================================

(deftest dismiss-popover-test
  (testing "clears captured-path, anchor-path, and locator-debug-path"
    (is (= [[:effects/save excerpt-state/captured-path nil]
            [:effects/save excerpt-state/anchor-path nil]
            [:effects/save excerpt-state/locator-debug-path nil]]
           (excerpt/dismiss-popover {})))))
