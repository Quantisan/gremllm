(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.chat :as chat-ui]
            [lookup.core :as lookup]))

(deftest render-input-form-test
  (testing ":on-submit handler has correct structure"
    (let [hiccup        (chat-ui/render-input-form "some input" false true)
          form-hiccup   (lookup/select-one 'form hiccup)
          form-attrs    (lookup/attrs form-hiccup)
          on-submit-val (get-in form-attrs [:on :submit])]
      (is (some? form-hiccup) "A :form element should be rendered.")
      (is (vector? on-submit-val) "The value of :on-submit should be a vector.")
      (is (every? vector? on-submit-val) "Each item in the :on-submit vector should also be a vector (i.e., an action tuple).")
      (is (= [[:effects/prevent-default] [:form.actions/submit]] on-submit-val) "The on-submit actions should be correct and in order."))))
