(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-sync-data-js
  "Create document sync data with Malli defaults, optionally overriding fields"
  [& [overrides]]
  (-> (m/decode codec/DocumentSyncData
                {:document-meta {:name "Test Document"}}
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
  (testing "Empty document initializes new topic"
    (let [sync-data (create-sync-data-js)
          effects (document/opened {} sync-data)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (has-action? effects :document.actions/initialize-empty))))

  (testing "Document with topics restores them"
    (let [topic (schema/create-topic)
          sync-data (create-sync-data-js {:topics {"tid" topic}})
          effects (document/opened {} sync-data)
          [_ restore-params] (get-action effects :document.actions/restore-with-topics)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (= "tid" (:active-topic-id restore-params)))
      (is (contains? (:topics restore-params) "tid"))))

)

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

