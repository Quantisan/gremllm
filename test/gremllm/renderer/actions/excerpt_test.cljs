(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema-test :as schema-test]))

;; ========================================
;; capture
;; ========================================

(deftest capture-test
  (testing "nil selection data - dispatches dismiss-popover"
    (let [result (excerpt/capture {} nil)]
      (is (= [[:excerpt.actions/dismiss-popover]] result))))

  (testing "valid selection data - saves captured data and triggers popover position"
    (let [result (excerpt/capture {} schema-test/single-word-selection)]
      (is (= 2 (count result)))
      (is (= :effects/save (ffirst result)))
      (is (= excerpt-state/captured-path (second (first result))))
      (is (= schema-test/single-word-selection (nth (first result) 2)))
      (is (= :excerpt.actions/compute-popover-position (first (second result))))
      (is (= schema-test/single-word-selection (second (second result)))))))

;; ========================================
;; compute-popover-position
;; ========================================

(def panel-rect {:top 100 :left 50 :width 800 :height 600})
(def panel-scroll-top 20)

(def single-rect-captured
  {:range {:client-rects [{:top 200 :left 150 :height 30 :width 100}]}
   :panel-rect panel-rect
   :panel-scroll-top panel-scroll-top})

;; top  = (200 + 30) - 100 + 20 = 150
;; left = 150 - 50             = 100
(def expected-single-rect-position {:top 150 :left 100})

(def multi-rect-captured
  {:range {:client-rects [{:top 100 :left 80 :height 20 :width 200}
                          {:top 120 :left 80 :height 20 :width 180}
                          {:top 140 :left 80 :height 20 :width 90}]}
   :panel-rect panel-rect
   :panel-scroll-top panel-scroll-top})

;; Uses last rect: top=140, height=20, left=80
;; top  = (140 + 20) - 100 + 20 = 80
;; left = 80 - 50               = 30
(def expected-multi-rect-position {:top 80 :left 30})

(deftest compute-popover-position-test
  (testing "single client-rect - returns save effect with correct position"
    (let [result (excerpt/compute-popover-position {} single-rect-captured)]
      (is (= [[:effects/save excerpt-state/popover-path expected-single-rect-position]]
             result))))

  (testing "multiple client-rects - uses last rect for positioning"
    (let [result (excerpt/compute-popover-position {} multi-rect-captured)]
      (is (= [[:effects/save excerpt-state/popover-path expected-multi-rect-position]]
             result))))

  (testing "nil captured-data - returns nil (no-op)"
    (is (nil? (excerpt/compute-popover-position {} nil))))

  (testing "missing panel-rect - returns nil (no-op)"
    (let [no-panel (dissoc single-rect-captured :panel-rect)]
      (is (nil? (excerpt/compute-popover-position {} no-panel))))))

;; ========================================
;; dismiss-popover
;; ========================================

(deftest dismiss-popover-test
  (testing "returns save effect clearing popover state"
    (is (= [[:effects/save excerpt-state/popover-path nil]]
           (excerpt/dismiss-popover {})))))
