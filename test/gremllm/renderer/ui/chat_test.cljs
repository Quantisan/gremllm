(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.chat :as chat-ui]
            [lookup.core :as lookup]))

(deftest render-input-form-test
  (testing ":on-submit handler has correct structure"
    (let [hiccup (chat-ui/render-input-form "some input" false true)
          form   (lookup/select-one 'form hiccup)]
      (is (some? form) "A :form element should be rendered.")
      (is (= [[:effects/prevent-default] [:form.actions/submit]]
             (-> form lookup/attrs (get-in [:on :submit])))
          "The on-submit actions should be correct and in order."))))
