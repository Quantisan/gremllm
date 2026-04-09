(ns gremllm.renderer.state.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.state.excerpt :as excerpt-state]))

(def panel-rect {:top 100 :left 50 :width 800 :height 600})
(def anchor-context {:panel-rect panel-rect :panel-scroll-top 20})

(def single-rect-selection
  {:range {:client-rects [{:top 200 :left 150 :height 30 :width 100}]}})
;; top  = (200 + 30) - 100 + 20 = 150
;; left = 150 - 50             = 100

(def multi-rect-selection
  {:range {:client-rects [{:top 100 :left 80 :height 20 :width 200}
                          {:top 120 :left 80 :height 20 :width 180}
                          {:top 140 :left 80 :height 20 :width 90}]}})
;; Uses last rect: top=140, height=20, left=80
;; top  = (140 + 20) - 100 + 20 = 80
;; left = 80 - 50               = 30

(deftest popover-position-test
  (testing "single client-rect returns correct position"
    (is (= {:top 150 :left 100}
           (excerpt-state/popover-position single-rect-selection anchor-context))))

  (testing "multiple client-rects uses last rect"
    (is (= {:top 80 :left 30}
           (excerpt-state/popover-position multi-rect-selection anchor-context))))

  (testing "nil captured-selection returns nil"
    (is (nil? (excerpt-state/popover-position nil anchor-context))))

  (testing "nil anchor-context returns nil"
    (is (nil? (excerpt-state/popover-position single-rect-selection nil))))

  (testing "missing panel-rect in anchor returns nil"
    (is (nil? (excerpt-state/popover-position single-rect-selection {})))))
