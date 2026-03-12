(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]))

(def valid-selection
  {:text "Dispatch"
   :is-collapsed false
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 19
   :focus-node "#text"
   :focus-offset 27
   :range {:bounding-rect {:height 33 :left 350.96875 :top 27 :width 117.140625}
           :client-rects  [{:height 33 :left 350.96875 :top 27 :width 117.140625}]
           :common-ancestor "#text"
           :start-container "#text"
           :start-text "Pangalactic Wombat Dispatch"
           :start-offset 19
           :end-container "#text"
           :end-text "Pangalactic Wombat Dispatch"
           :end-offset 27}})

(deftest capture-test
  (testing "nil selection data - no-op"
    (is (nil? (excerpt/capture {} nil))))

  (testing "collapsed selection - no-op"
    (is (nil? (excerpt/capture {} {:text "" :is-collapsed true}))))

  (testing "valid selection data - saves to captured path"
    (let [result (excerpt/capture {} valid-selection)]
      (is (= 1 (count result)))
      (is (= :effects/save (ffirst result)))
      (is (= excerpt-state/captured-path (second (first result))))
      (is (= valid-selection (nth (first result) 2))))))
