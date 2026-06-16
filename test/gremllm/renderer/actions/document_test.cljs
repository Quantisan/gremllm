(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.schema :as schema]
            [gremllm.schema-test :as schema-test]
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
  (testing "Empty document initializes empty"
    (let [sync-data (create-sync-data-js)
          effects (document/opened {} sync-data)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (has-action? effects :document.actions/initialize-empty))))

  (testing "Document with topics restores them"
    (let [topic (schema/create-topic schema-test/anchor-fixture)
          sync-data (create-sync-data-js {:topics {"tid" topic}})
          effects (document/opened {} sync-data)
          [_ restore-params] (get-action effects :document.actions/restore-with-topics)]
      (is (has-action? effects :document.actions/set-meta))
      (is (has-action? effects :document.actions/set-content))
      (is (contains? (:topics restore-params) "tid"))
      (is (not (contains? restore-params :active-topic-id))
          "opened no longer passes active-topic-id — restore derives it"))))

;; Intentional layer split: session_test proves *which* topic is most-recent
;; (the selector logic); this test proves restore-with-topics *wires* set-active
;; to that selector's result. Keep both — they cover different layers.
(deftest restore-with-topics-test
  (let [block {:kind :paragraph :index 1 :start-line 1 :end-line 1 :block-text-snippet "x"}
        anchor {:id "e1" :text "x" :locator {:document-relative-path "d.md"
                                              :start-block block :end-block block}}]
    (testing "Activates most recent anchored topic"
      (let [old-topic (assoc (schema/create-topic schema-test/anchor-fixture) :id "topic-1000-a")
            new-topic (assoc (schema/create-topic anchor) :id "topic-2000-b")
            effects (document/restore-with-topics {} {:topics {"topic-1000-a" old-topic
                                                                "topic-2000-b" new-topic}})
            [_ activated-id] (get-action effects :topic.actions/set-active)]
        (is (= "topic-2000-b" activated-id))))

    (testing "No activation when no anchored topics"
      ;; Topics like this are rejected at the disk boundary post-slice-2 (topic-from-disk
      ;; throws on missing :anchor). This filter is defense in depth against malformed
      ;; in-memory state that bypassed the codec.
      (let [old-topic {:id "topic-1000-a" :name "New Topic"
                       :session {:pending-diffs []} :messages [] :excerpts []}
            effects (document/restore-with-topics {} {:topics {"topic-1000-a" old-topic}})]
        (is (not (has-action? effects :topic.actions/set-active)))))

    (testing "Restores all topics to state"
      (let [topic (assoc (schema/create-topic anchor) :id "tid")
            effects (document/restore-with-topics {} {:topics {"tid" topic}})
            [_ topics-path saved-topics] (get-action effects :effects/save)]
        (is (= [:topics] topics-path))
        (is (contains? saved-topics "tid"))))))
