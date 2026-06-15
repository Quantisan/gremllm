(ns gremllm.renderer.actions.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.ui :as ui]
            [gremllm.schema-test :as schema-test]))

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
                         :block-text-snippet "hello world"}}})

(deftest submit-without-text-is-noop-test
  (let [state {:form {:user-input ""}
               :active-topic-id "t1"
               :topics {"t1" {:excerpts []}}}]
    (is (nil? (ui/submit-messages state)))))

(deftest submit-without-excerpts-sends-plain-message-test
  ;; First-message send on a topic without an anchor — :messages [] is explicit
  (let [state {:form {:user-input "hello"}
               :active-topic-id "t1"
               :topics {"t1" {:id "t1" :messages [] :excerpts []}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ topic-id message] add-msg
        [_ sent-message] send]
    (is (= :messages.actions/add-to-chat (first add-msg)))
    (is (= "t1" topic-id))
    (is (= :user (:type message)))
    (is (= "hello" (:text message)))
    (is (not (contains? message :context)))
    (is (= :acp.actions/send-prompt (first send)))
    (is (= message sent-message))))

(deftest submit-with-excerpts-attaches-context-test
  ;; First-message send on a topic without an anchor — :messages [] is explicit
  (let [state {:form {:user-input "reword these"}
               :active-topic-id "t1"
               :topics {"t1" {:id "t1" :messages [] :excerpts [sample-excerpt]}}}
        [add-msg _ _ _ send] (ui/submit-messages state)
        [_ topic-id message] add-msg
        [_ sent-message] send]
    (is (= "t1" topic-id))
    (is (= "reword these" (:text message)))
    (is (= {:excerpts [sample-excerpt]} (:context message)))
    (is (= message sent-message))))

(deftest submit-first-message-injects-anchor-test
  (testing "first message in an anchored topic carries the anchor as context"
    (let [state {:form {:user-input "hello"}
                 :active-topic-id "t1"
                 :topics {"t1" {:id "t1" :anchor schema-test/anchor-fixture
                                :messages [] :excerpts []}}}
          [add-msg _ _ _ send] (ui/submit-messages state)
          [_ _ message] add-msg
          [_ sent-message] send]
      (is (= schema-test/anchor-fixture (get-in message [:context :anchor])))
      (is (not (contains? (:context message) :excerpts)))
      (is (= message sent-message)))))

(deftest submit-second-message-omits-anchor-test
  (testing "anchor is first-message-only"
    (let [state {:form {:user-input "follow-up"}
                 :active-topic-id "t1"
                 :topics {"t1" {:id "t1" :anchor schema-test/anchor-fixture
                                :messages [{:id 1 :type :user :text "first"}]
                                :excerpts []}}}
          [add-msg _ _ _ send] (ui/submit-messages state)
          [_ _ message] add-msg
          [_ sent-message] send]
      (is (nil? (:context message)))
      (is (= message sent-message)))))

(deftest submit-first-message-with-anchor-and-excerpts-test
  (testing "first message carries both anchor and excerpts in context"
    (let [state {:form {:user-input "reword this"}
                 :active-topic-id "t1"
                 :topics {"t1" {:id "t1" :anchor schema-test/anchor-fixture
                                :messages [] :excerpts [sample-excerpt]}}}
          [add-msg _ _ _ send] (ui/submit-messages state)
          [_ _ message] add-msg
          [_ sent-message] send]
      (is (= schema-test/anchor-fixture (get-in message [:context :anchor])))
      (is (= [sample-excerpt] (get-in message [:context :excerpts])))
      (is (= message sent-message)))))

(deftest test-handle-submit-keys
  (testing "Enter without Shift returns prevent-default and submit effects"
    (is (= [[:effects/prevent-default]
            [:form.actions/submit]]
           (ui/handle-submit-keys {} {:key "Enter" :shift? false}))))

  (testing "Shift+Enter returns nil to allow newline"
    (is (nil? (ui/handle-submit-keys {} {:key "Enter" :shift? true})))))
