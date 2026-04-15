(ns gremllm.renderer.actions.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.ui :as ui]))

(def sample-excerpt
  {:id "e1"
   :text "hello"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph
                           :index 2
                           :start-line 3
                           :end-line 3
                           :block-text-snippet "hello world"}
             :end-block {:kind :paragraph
                         :index 2
                         :start-line 3
                         :end-line 3
                         :block-text-snippet "hello world"}
             :start-offset 0
             :end-offset 5}})

(deftest submit-without-text-is-noop-test
  (let [state {:form {:user-input ""}
               :active-topic-id "t1"
               :topics {"t1" {:excerpts []}}}]
    (is (nil? (ui/submit-messages state)))))

(deftest submit-without-excerpts-sends-plain-message-test
  (let [state {:form {:user-input "hello"}
               :active-topic-id "t1"
               :topics {"t1" {:excerpts []}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ message] add-msg
        [_ sent-message] send]
    (is (= :messages.actions/add-to-chat (first add-msg)))
    (is (= :user (:type message)))
    (is (= "hello" (:text message)))
    (is (not (contains? message :context)))
    (is (= :acp.actions/send-prompt (first send)))
    (is (= message sent-message))))

(deftest submit-with-excerpts-attaches-context-test
  (let [state {:form {:user-input "reword these"}
               :active-topic-id "t1"
               :topics {"t1" {:excerpts [sample-excerpt]}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ message] add-msg
        [_ sent-message] send]
    (is (= "reword these" (:text message)))
    (is (= {:excerpts [sample-excerpt]} (:context message)))
    (is (= message sent-message))))

(deftest test-handle-submit-keys
  (testing "Enter without Shift returns prevent-default and submit effects"
    (is (= [[:effects/prevent-default]
            [:form.actions/submit]]
           (ui/handle-submit-keys {} {:key "Enter" :shift? false}))))

  (testing "Shift+Enter returns nil to allow newline"
    (is (nil? (ui/handle-submit-keys {} {:key "Enter" :shift? true})))))
