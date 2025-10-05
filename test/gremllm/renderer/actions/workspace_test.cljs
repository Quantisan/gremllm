(ns gremllm.renderer.actions.workspace-test
  (:require [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.test-utils :refer [with-console-silenced]]
            [gremllm.schema :as schema]
            [cljs.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-workspace-data-js
  "Create workspace data with Malli defaults, optionally overriding fields"
  [& [overrides]]
  (-> (m/decode schema/WorkspaceSyncData
                {:workspace {:name "Test Workspace"}}
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

(deftest opened-test
  (testing "Empty workspace initializes new topic"
    (let [workspace-data (create-workspace-data-js)
          effects (workspace/opened {} workspace-data)]
      (is (has-action? effects :workspace.actions/set))
      (is (has-action? effects :workspace.actions/initialize-empty))))

  (testing "Workspace with topics restores them"
    (let [topic (schema/create-topic)
          workspace-data (create-workspace-data-js {:topics {"tid" topic}})
          effects (workspace/opened {} workspace-data)
          [_ restore-params] (get-action effects :workspace.actions/restore-with-topics)]
      (is (has-action? effects :workspace.actions/set))
      (is (= "tid" (:active-topic-id restore-params)))
      (is (contains? (:topics restore-params) "tid")))))

(deftest restore-with-topics-test
  (testing "Passes model from active topic to set-active"
    (let [topic (assoc (schema/create-topic) :model "claude-3-5-sonnet-20241022")
          effects (workspace/restore-with-topics {} {:topics          {"tid" topic}
                                                      :active-topic-id "tid"})
          [_ topic-id model] (get-action effects :topic.actions/set-active)]
      (is (= "tid" topic-id))
      (is (= "claude-3-5-sonnet-20241022" model))))

  (testing "Uses default model from schema when topic created"
    (let [topic (schema/create-topic)
          effects (workspace/restore-with-topics {} {:topics          {"tid" topic}
                                                      :active-topic-id "tid"})
          [_ _ model] (get-action effects :topic.actions/set-active)]
      (is (= "anthropic/claude-sonnet-4-5" model)))))

(deftest opened-multiple-topics-test
  (testing "Selects one topic when multiple exist"
    (let [topic1 (schema/create-topic)
          topic2 (schema/create-topic)
          workspace-data (create-workspace-data-js {:topics {"tid1" topic1
                                                              "tid2" topic2}})
          effects (workspace/opened {} workspace-data)
          [_ restore-params] (get-action effects :workspace.actions/restore-with-topics)
          selected-id (:active-topic-id restore-params)]
      (is (contains? #{"tid1" "tid2"} selected-id))
      (is (= 2 (count (:topics restore-params)))))))

(deftest load-error-test
  (testing "Returns no effects on error"
    (with-console-silenced
      (let [effects (workspace/load-error {} (js/Error. "Test error"))]
        (is (empty? effects))))))

