(ns gremllm.renderer.actions.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.ui :as ui]))

(deftest test-submit-message
  (testing "does nothing with empty input"
    (is (nil? (ui/submit-messages {:form            {:user-input ""}
                                   :active-topic-id "t1"
                                   :topics          {"t1" {:model "claude-3-5-haiku-latest"}}}))))

  (testing "submits message with valid input, reads model from active topic"
    (let [effects (ui/submit-messages {:form            {:user-input "Hello"}
                                       :active-topic-id "t1"
                                       :topics          {"t1" {:model "claude-3-5-haiku-latest"}}})]
      (is (= 5 (count effects)))
      (is (= :messages.actions/add-to-chat (ffirst effects)))
      (is (= :form.actions/clear-input (first (second effects))))
      (is (= :acp.actions/send-prompt (first (last effects)))))))

(deftest test-handle-submit-keys
  (testing "Enter without Shift returns prevent-default and submit effects"
    (is (= [[:effects/prevent-default]
            [:form.actions/submit]]
           (ui/handle-submit-keys {} {:key "Enter" :shift? false}))))

  (testing "Shift+Enter returns nil to allow newline"
    (is (nil? (ui/handle-submit-keys {} {:key "Enter" :shift? true})))))
