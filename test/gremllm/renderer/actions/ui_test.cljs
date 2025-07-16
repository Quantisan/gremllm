(ns gremllm.renderer.actions.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.ui :as ui]))

(deftest test-submit-message
  (testing "does nothing with empty input"
    (is (nil? (ui/submit-messages {:form {:user-input ""}}))))

  (testing "submits message with valid input"
    (let [effects (ui/submit-messages {:form {:user-input "Hello"}})]
      (is (= 6 (count effects)))
      (is (= :msg.actions/add (ffirst effects)))
      (is (= :form.effects/clear-input (first (second effects))))
      (is (= :llm.effects/send-llm-messages (first (last effects)))))))
