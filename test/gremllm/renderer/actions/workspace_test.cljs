(ns gremllm.renderer.actions.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-workspace-data-js
  "Create workspace data with Malli defaults, optionally overriding fields"
  [& [overrides]]
  (-> (m/decode schema/WorkspaceSyncData
                {:path "/test/path"}  ; Provide required path
                mt/default-value-transformer)
      (merge overrides)
      clj->js))

(deftest opened-test
  (testing "Empty workspace initializes new"
    (let [workspace-data-js (create-workspace-data-js)  ; Now just uses defaults
          result (workspace/opened {} workspace-data-js)]
      (is (= [[:workspace.actions/initialize-empty]] result))))

  (testing "Workspace with topics restores them"
    (let [topic (schema/create-topic)
          workspace-data-js (create-workspace-data-js {:topics {"tid" topic}})
          result (workspace/opened {} workspace-data-js)
          [[action-type params]] result]
      (is (= :workspace.actions/restore-with-topics action-type))
      (is (= "tid" (:active-topic-id params)))
      (is (contains? (:topics params) "tid")))))

