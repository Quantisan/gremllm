(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.test-utils :refer [with-console-error-silenced]]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-sync-data-js
  "Create document sync data with Malli defaults, optionally overriding fields"
  [& [overrides]]
  (-> (m/decode codec/DocumentSyncData
                {:document-meta {:name "Test Workspace"}}
                mt/default-value-transformer)
      (merge overrides)
      clj->js))

;; Test helpers for readable assertions
(defn has-action? [effects action-kw]
  (some #(= action-kw (first %)) effects))

(defn get-action [effects action-kw]
  (->> effects
       (filter #(= action-kw (first %)))
       first))

(deftest set-content-test
  (let [effects (document/set-content {} "# Replaced")]
    (testing "saves the new content first"
      (is (= [:effects/save document-state/content-path "# Replaced"]
             (first effects))))))

(deftest opened-test
  (testing "Empty workspace initializes new topic"
    (let [workspace-data (create-sync-data-js)
          effects (document/opened {} workspace-data)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (has-action? effects :document.actions/initialize-empty))))

  (testing "Workspace with topics restores them"
    (let [topic (schema/create-topic)
          workspace-data (create-sync-data-js {:topics {"tid" topic}})
          effects (document/opened {} workspace-data)
          [_ restore-params] (get-action effects :document.actions/restore-with-topics)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (= "tid" (:active-topic-id restore-params)))
      (is (contains? (:topics restore-params) "tid"))))

  (testing "Workspace with document content dispatches set-content"
    (let [workspace-data (create-sync-data-js {:document {:content "# Test Document"}})
          effects (document/opened {} workspace-data)
          [_ content] (get-action effects :document.actions/set-content)]
      (is (has-action? effects :document.actions/set-content))
      (is (= "# Test Document" content)))))

(deftest restore-with-topics-test
  (testing "Sets active topic without model param"
    (let [topic (schema/create-topic)
          effects (document/restore-with-topics {} {:topics          {"tid" topic}
                                                     :active-topic-id "tid"})
          [_ topic-id] (get-action effects :topic.actions/set-active)]
      (is (= "tid" topic-id))
      (is (= 2 (count (get-action effects :topic.actions/set-active)))
          "should only pass topic-id, not model")))

  (testing "Restores all topics to state"
    (let [topic (schema/create-topic)
          effects (document/restore-with-topics {} {:topics          {"tid" topic}
                                                     :active-topic-id "tid"})
          [_ topics-path saved-topics] (get-action effects :effects/save)]
      (is (= [:topics] topics-path))
      (is (contains? saved-topics "tid")))))

(deftest opened-multiple-topics-test
  (testing "Selects one topic when multiple exist"
    (let [topic1 (schema/create-topic)
          topic2 (schema/create-topic)
          workspace-data (create-sync-data-js {:topics {"tid1" topic1
                                                         "tid2" topic2}})
          effects (document/opened {} workspace-data)
          [_ restore-params] (get-action effects :document.actions/restore-with-topics)
          selected-id (:active-topic-id restore-params)]
      (is (contains? #{"tid1" "tid2"} selected-id))
      (is (= 2 (count (:topics restore-params)))))))

(deftest load-error-test
  (testing "Returns no effects on error"
    (with-console-error-silenced
      (let [effects (document/load-error {} (js/Error. "Test error"))]
        (is (empty? effects))))))
