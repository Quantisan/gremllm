(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]))

(deftest capture-test
  (testing "nil selection data - no-op"
    (is (nil? (excerpt/capture {} nil))))

  (testing "collapsed selection - no-op"
    (is (nil? (excerpt/capture {} {:text "" :is-collapsed true}))))

  (testing "valid selection data - saves to captured path"
    (let [selection {:text "hello" :is-collapsed false :range-count 1}
          result    (excerpt/capture {} selection)]
      (is (= 1 (count result)))
      (is (= :effects/save (ffirst result)))
      (is (= excerpt-state/captured-path (second (first result))))
      (is (= selection (nth (first result) 2))))))
