(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.schema :as schema]
            [gremllm.schema-test :as schema-test]
            [gremllm.schema.codec :as codec]
            [malli.core :as m])
  (:require-macros [gremllm.test-utils :refer [with-console-error-silenced]]))

(deftest start-anchored-session-test
  (let [anchor {:id "excerpt-abc"
                :text "launched on a Tuesday"
                :locator {:document-relative-path "document.md"
                          :start-block {:kind :paragraph :index 2
                                        :start-line 3 :end-line 3
                                        :block-text-snippet "Our Gremllm"}
                          :end-block {:kind :paragraph :index 2
                                      :start-line 3 :end-line 3
                                      :block-text-snippet "Our Gremllm"}}}
        result (topic/start-anchored-session {} anchor)
        [[_ topic-path saved-topic] [set-active-action set-active-id] dismiss-action] result]

    (is (= :effects/save (first (first result))))
    (is (= anchor (:anchor saved-topic)) "anchor is set on the new topic")
    (is (= "New Topic" (:name saved-topic)))
    (is (= (topic-state/topic-path (:id saved-topic)) topic-path))

    (is (= :topic.actions/set-active set-active-action))
    (is (= (:id saved-topic) set-active-id))

    (is (= [:excerpt.actions/dismiss-popover] dismiss-action))))

(deftest start-session-from-capture-test
  (let [block {:kind :paragraph :index 2
               :start-line 3 :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}
        ;; capture->excerpt reads only (:text captured); the selection geometry
        ;; the real capture carries is irrelevant to what this action builds.
        state {:excerpt {:captured {:text "launched on a Tuesday"}
                         :locator-hints {:document-relative-path "document.md"
                                         :start-block block
                                         :end-block block}}}
        result (topic/start-session-from-capture state)]
    (testing "dispatches start-anchored-session with the built anchor"
      (let [[[action-name anchor]] result]
        (is (= 1 (count result)) "dispatches a single nested action")
        (is (= :topic.actions/start-anchored-session action-name))
        (is (= "launched on a Tuesday" (:text anchor)))))
    (testing "returns nil when no captured state"
      (is (nil? (topic/start-session-from-capture {}))))))

(def ^:private expected-new-topic (schema/create-topic schema-test/anchor-fixture))

(deftest normalize-topic-test
  (let [denormalized (assoc expected-new-topic
                            :messages [{:id 1 :type "user" :text "test"}
                                       {:id 2 :type "assistant" :text "response"}])
        expected     (assoc expected-new-topic
                            :messages [{:id 1 :type :user :text "test"}
                                       {:id 2 :type :assistant :text "response"}])]
    (is (= expected (codec/topic-from-ipc denormalized))
        "should convert message types from strings to keywords")))

(deftest set-active-inits-acp-test
  (testing "set-active saves the id and triggers ACP session init"
    (is (= [[:effects/save topic-state/active-topic-id-path "topic-123"]
            [:acp.effects/init-session "topic-123"]]
           (topic/set-active {} "topic-123")))))

(deftest auto-save-test
  (let [topic-id       "topic-123"
        empty-topic    {:id topic-id :messages []}
        topic-with-msg {:id topic-id :messages [{:id "m1" :type :user :content "hi"}]}]

    (testing "should not trigger save when topic has no messages"
      (let [state  {:topics {topic-id empty-topic}}
            actions (topic/auto-save state topic-id)]
        (is (nil? actions))))

    (testing "should trigger save when topic has messages"
      (let [state  {:topics {topic-id topic-with-msg}}
            actions (topic/auto-save state topic-id)]
        (is (= [[:topic.effects/save-topic topic-id]] actions))))))

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

(deftest auto-save-fires-when-excerpts-present-with-no-messages-test
  (let [state {:active-topic-id "t1"
               :topics {"t1" {:id "t1"
                              :messages []
                              :excerpts [sample-excerpt]}}}]
    (is (= [[:topic.effects/save-topic "t1"]]
           (topic/auto-save state "t1")))))

(deftest finalize-turn-test
  (let [topic-id "t1"]
    (is (= [[:excerpt.actions/consume topic-id]
            [:topic.actions/mark-unsaved topic-id]
            [:topic.effects/auto-save topic-id]]
           (topic/finalize-turn {} topic-id)))))

(def ^:private sample-options
  [{:kind "allow_always" :option-id "allow_always" :name "Always Allow"}
   {:kind "allow_once"   :option-id "allow"        :name "Allow"}
   {:kind "reject_once"  :option-id "reject"       :name "Reject"}])

(deftest append-pending-permission-test
  (testing "appends diffs tagged with tool-call-id and stashes options keyed by tool-call-id"
    (let [topic-id "t1"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs []}}}}
          enriched {:tool-call {:tool-call-id "tc-1"
                                :content [{:type "diff" :path "/p" :old-text "a" :new-text "b"}]}
                    :options sample-options}
          actions  (topic/append-pending-permission state enriched)]
      (is (= [[:effects/save
               (topic-state/pending-diffs-path topic-id)
               [{:type "diff" :path "/p" :old-text "a" :new-text "b" :tool-call-id "tc-1"}]]
              [:effects/save
               (topic-state/pending-permission-options-path topic-id)
               {"tc-1" sample-options}]]
             actions))))
  (testing "preserves existing pending-permission-options when a second permission lands"
    (let [topic-id "t1"
          existing-options [{:kind "allow_once" :option-id "ok" :name "ok"}]
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs []
                                                 :pending-permission-options {"tc-prev" existing-options}}}}}
          enriched {:tool-call {:tool-call-id "tc-1"
                                :content [{:type "diff" :path "/p" :old-text "a" :new-text "b"}]}
                    :options sample-options}
          actions  (topic/append-pending-permission state enriched)]
      (is (= {"tc-prev" existing-options "tc-1" sample-options}
             (nth (second actions) 2)))))
  (testing "is a no-op when permission carries no diff content"
    (let [state    {:active-topic-id "t1"
                    :topics {"t1" {:id "t1" :session {:pending-diffs []}}}}
          enriched {:tool-call {:tool-call-id "tc-1" :content []}
                    :options sample-options}]
      (is (nil? (topic/append-pending-permission state enriched))))))

(deftest accept-diff-test
  (testing "looks up allow_once option-id, emits IPC resolve, clears pending-diffs/options, records resolved"
    (let [topic-id "t1"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs
                                                 [{:type "diff" :path "/p" :old-text "a" :new-text "b" :tool-call-id "tc-1"}
                                                  {:type "diff" :path "/q" :old-text "x" :new-text "y" :tool-call-id "tc-2"}]
                                                 :pending-permission-options {"tc-1" sample-options
                                                                              "tc-2" sample-options}
                                                 :resolved-tool-calls #{}}}}}
          actions  (topic/accept-diff state "tc-1")]
      (is (= [[:acp.effects/resolve-permission "tc-1" "allow"]
              [:effects/save
               (topic-state/pending-diffs-path topic-id)
               [{:type "diff" :path "/q" :old-text "x" :new-text "y" :tool-call-id "tc-2"}]]
              [:effects/save
               (topic-state/pending-permission-options-path topic-id)
               {"tc-2" sample-options}]
              [:effects/save
               (topic-state/resolved-tool-calls-path topic-id)
               #{"tc-1"}]]
             actions)))))

(deftest reject-diff-test
  (testing "looks up reject_once option-id, emits IPC reject, clears pending-diffs/options, records resolved"
    (let [topic-id "t1"
          state    {:active-topic-id topic-id
                    :topics {topic-id {:id topic-id
                                       :session {:pending-diffs
                                                 [{:type "diff" :path "/p" :old-text "a" :new-text "b" :tool-call-id "tc-1"}]
                                                 :pending-permission-options {"tc-1" sample-options}
                                                 :resolved-tool-calls #{"tc-prev"}}}}}
          actions  (topic/reject-diff state "tc-1")]
      (is (= [[:acp.effects/resolve-permission "tc-1" "reject"]
              [:effects/save
               (topic-state/pending-diffs-path topic-id)
               []]
              [:effects/save
               (topic-state/pending-permission-options-path topic-id)
               {}]
              [:effects/save
               (topic-state/resolved-tool-calls-path topic-id)
               #{"tc-prev" "tc-1"}]]
             actions)))))

(deftest resolve-diff-missing-kind-test
  (testing "logs and emits no resolve effect when stashed options omit the requested kind"
    (with-console-error-silenced
      (let [topic-id "t1"
            options-without-allow [{:kind "reject_once" :option-id "reject" :name "Reject"}]
            state    {:active-topic-id topic-id
                      :topics {topic-id {:id topic-id
                                         :session {:pending-diffs
                                                   [{:type "diff" :path "/p" :old-text "a" :new-text "b" :tool-call-id "tc-1"}]
                                                   :pending-permission-options {"tc-1" options-without-allow}
                                                   :resolved-tool-calls #{}}}}}
            actions  (topic/accept-diff state "tc-1")]
        (is (not-any? #(= :acp.effects/resolve-permission (first %)) actions)
            "no resolve-permission effect when kind not found")))))
