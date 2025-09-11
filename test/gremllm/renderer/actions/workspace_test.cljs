(ns gremllm.renderer.actions.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.schema :as schema]))

(deftest opened-test
  (testing "Empty workspace initializes new"
    (let [workspace-data-js #js {:path "/path" :topics #js {}}
          result (workspace/opened {} workspace-data-js)]
      (is (= [[:workspace.actions/initialize-empty]] result))))

  (testing "Workspace with topics restores them"
    (let [topic (schema/create-topic)
          workspace-data-js (clj->js {:path "/path" 
                                      :topics {"tid" topic}})
          result (workspace/opened {} workspace-data-js)
          [[action-type topics active-id]] result]
      (is (= :workspace.actions/restore-with-topics action-type))
      (is (= "tid" active-id))
      (is (contains? topics "tid")))))

