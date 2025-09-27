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
                {:workspace {:name "Test Workspace"}}  ; Provide required workspace meta
                mt/default-value-transformer)
      (merge overrides)
      clj->js))

(deftest opened-test
  (testing "Empty workspace initializes new"
    (let [workspace-data-js (create-workspace-data-js)
          result (workspace/opened {} workspace-data-js)
          [[action1 action1-param] [action2]] result]
      (is (= :workspace.actions/set action1))
      (is (= {:name "Test Workspace"} action1-param))
      (is (= :workspace.actions/initialize-empty action2))))

  (testing "Workspace with topics restores them"
    (let [topic (schema/create-topic)
          workspace-data-js (create-workspace-data-js {:topics {"tid" topic}})
          result (workspace/opened {} workspace-data-js)
          [[action1 action1-param] [action2 action2-param]] result]
      (is (= :workspace.actions/set action1))
      (is (= {:name "Test Workspace"} action1-param))
      (is (= :workspace.actions/restore-with-topics action2))
      (is (= "tid" (:active-topic-id action2-param)))
      (is (contains? (:topics action2-param) "tid")))))

