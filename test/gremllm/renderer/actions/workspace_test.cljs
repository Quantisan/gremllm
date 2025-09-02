(ns gremllm.renderer.actions.workspace-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.schema :as schema]))

(deftest test-import-workspace-topics
  (testing "transforms and selects first topic as active"
    (let [topic-1 (schema/create-topic)
          topic-2 (assoc (schema/create-topic) :id "topic-2")
          input {(:id topic-1) topic-1
                 (:id topic-2) topic-2}
          {:keys [topics active-id]} (workspace/import-workspace-topics input)]
      
      (is (= 2 (count topics)))
      (is (= (:id topic-1) active-id))
      (is (contains? topics (:id topic-1)))
      (is (contains? topics (:id topic-2)))))
  
  (testing "handles empty topics map"
    (let [{:keys [topics active-id]} (workspace/import-workspace-topics {})]
      (is (= {} topics))
      (is (nil? active-id))))
  
  (testing "handles nil input - workspace-from-ipc handles nil gracefully"
    (let [{:keys [topics active-id]} (workspace/import-workspace-topics nil)]
      (is (= {} topics))
      (is (nil? active-id)))))
