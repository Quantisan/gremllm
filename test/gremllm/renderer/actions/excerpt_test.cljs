(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema-test :as schema-test]))

(deftest capture-test
  (testing "nil selection data - no-op"
    (is (nil? (excerpt/capture {} nil))))

  (testing "valid selection data - saves to captured path"
    (let [result (excerpt/capture {} schema-test/single-word-selection)]
      (is (= 1 (count result)))
      (is (= :effects/save (ffirst result)))
      (is (= excerpt-state/captured-path (second (first result))))
      (is (= schema-test/single-word-selection (nth (first result) 2))))))
