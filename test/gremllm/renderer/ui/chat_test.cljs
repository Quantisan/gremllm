(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.schema-test :as schema-test]
            [lookup.core :as lookup]))

(deftest render-input-form-test
  (testing ":on-submit handler has correct structure"
    (let [hiccup (chat-ui/render-input-form
                   {:input-value    "some input"
                    :loading?       false
                    :has-any-api-key?   true})
          form   (lookup/select-one 'form hiccup)]
      (is (some? form) "A :form element should be rendered.")
      (is (= [[:effects/prevent-default] [:form.actions/submit]]
             (-> form lookup/attrs (get-in [:on :submit])))
          "The on-submit actions should be correct and in order."))))

(defn- flatten-strings [hiccup]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [x]
        (when (string? x)
          (swap! acc conj x))
        x)
      hiccup)
    @acc))

(defn- contains-text? [hiccup s]
  (some #(str/includes? % s) (flatten-strings hiccup)))

(def same-block-excerpt
  {:id "e1"
   :text "this is a selection longer than forty characters abc"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph
                           :index 3
                           :start-line 5
                           :end-line 5
                           :block-text-snippet "full block text"}
             :end-block {:kind :paragraph
                         :index 3
                         :start-line 5
                         :end-line 5
                         :block-text-snippet "full block text"}}})

(deftest plain-user-message-renders-text-test
  (let [hiccup (chat-ui/render-chat-area
                [(schema-test/create-message {:id 1 :type :user :text "hello"})]
                false)]
    (is (contains-text? hiccup "hello"))))

(deftest user-message-with-excerpts-renders-smoke-test
  (let [hiccup (chat-ui/render-chat-area
                [(schema-test/create-message
                  {:id 1
                   :type :user
                   :text "reword these"
                   :context {:excerpts [same-block-excerpt]}})]
                false)]
    (is (contains-text? hiccup "reword these"))))
