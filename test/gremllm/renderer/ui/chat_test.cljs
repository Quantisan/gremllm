(ns gremllm.renderer.ui.chat-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.schema-test :as schema-test]
            [lookup.core :as lookup]))

(def ^:private active-session-opts
  {:active-topic {:id "t1" :name "T" :session {:id "s1"}}
   :active-topic-id "t1"})

(deftest render-input-form-test
  (testing ":on-submit handler has correct structure"
    (let [hiccup (chat-ui/render-input-form
                   {:input-value "some input"
                    :loading?    false})
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
                false
                active-session-opts)]
    (is (contains-text? hiccup "hello"))))

(deftest user-message-with-excerpts-renders-smoke-test
  (let [hiccup (chat-ui/render-chat-area
                [(schema-test/create-message
                  {:id 1
                   :type :user
                   :text "reword these"
                   :context {:excerpts [same-block-excerpt]}})]
                false
                active-session-opts)]
    (is (contains-text? hiccup "reword these"))))

(deftest tool-call-web-search-renders-test
  (testing "completed :web-search renders 'Searched the web' + query"
    (let [hiccup (chat-ui/render-chat-area
                   [{:id 1
                     :type :tool-call
                     :tool-call-id "toolu_ws"
                     :tool :web-search
                     :tool-call-status "completed"
                     :query "CRDT vs OT"
                     :text ""}]
                   false
                   active-session-opts)]
      (is (contains-text? hiccup "Searched the web"))
      (is (contains-text? hiccup "CRDT vs OT"))))

  (testing "pending :web-search renders 'Searching the web'"
    (let [hiccup (chat-ui/render-chat-area
                   [{:id 2
                     :type :tool-call
                     :tool-call-id "toolu_ws2"
                     :tool :web-search
                     :tool-call-status "pending"
                     :query nil
                     :text ""}]
                   false
                   active-session-opts)]
      (is (contains-text? hiccup "Searching the web")))))

(deftest tool-call-read-renders-test
  (testing ":read renders the display label from :text"
    (let [hiccup (chat-ui/render-chat-area
                   [{:id 3
                     :type :tool-call
                     :tool-call-id "toolu_r"
                     :tool :read
                     :tool-call-status "completed"
                     :text "Read — foo.md (12 lines)"}]
                   false
                   active-session-opts)]
      (is (contains-text? hiccup "Read — foo.md (12 lines)")))))

(deftest no-active-session-prompt-test
  (testing "renders prompt when no active session"
    (let [hiccup (chat-ui/render-chat-area [] false {:active-topic nil
                                                      :active-topic-id nil})]
      (is (contains-text? hiccup "Select text")))))

(deftest shell-session-shows-anchor-context-test
  (testing "shell session shows anchor text context"
    (let [topic {:id "t1" :name "T" :anchor {:id "e1" :text "sample anchor text" :locator {}}
                 :session {}}
          hiccup (chat-ui/render-chat-area [] false {:active-topic topic
                                                      :active-topic-id "t1"
                                                      :shell? true})]
      (is (contains-text? hiccup "sample anchor text")))))
