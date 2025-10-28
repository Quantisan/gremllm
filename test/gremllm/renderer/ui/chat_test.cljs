(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [gremllm.renderer.ui.chat :as chat-ui]
            [lookup.core :as lookup]))

(deftest render-input-form-test
  (testing ":on-submit handler has correct structure"
    (let [hiccup (chat-ui/render-input-form
                   {:input-value    "some input"
                    :selected-model "claude-sonnet-4-5"
                    :has-messages?  false
                    :loading?       false
                    :has-any-api-key?   true})
          form   (lookup/select-one 'form hiccup)]
      (is (some? form) "A :form element should be rendered.")
      (is (= [[:effects/prevent-default] [:form.actions/submit]]
             (-> form lookup/attrs (get-in [:on :submit])))
          "The on-submit actions should be correct and in order."))))

(deftest markdown->html-test
  (testing "renders basic markdown"
    (let [result (#'chat-ui/markdown->html "**bold** and `code`")]
      (is (str/includes? result "<strong>bold</strong>"))
      (is (str/includes? result "<code>code</code>"))))

  (testing "sanitizes script tags"
    (let [result (#'chat-ui/markdown->html "<script>alert('xss')</script>")]
      (is (not (str/includes? result "<script")))
      (is (not (str/includes? result "alert")))))

  (testing "sanitizes javascript: URLs"
    (let [result (#'chat-ui/markdown->html "[bad link](javascript:alert('xss'))")]
      (is (not (str/includes? result "javascript:")))
      (is (not (str/includes? result "alert")))))

  (testing "sanitizes event handlers"
    (let [result (#'chat-ui/markdown->html "<a onclick=\"alert('xss')\">click</a>")]
      (is (not (str/includes? result "onclick")))))

  (testing "allows safe links"
    (let [result (#'chat-ui/markdown->html "[safe](https://example.com)")]
      (is (str/includes? result "https://example.com")))))
