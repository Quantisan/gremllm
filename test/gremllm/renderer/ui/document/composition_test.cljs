(ns gremllm.renderer.ui.document.composition-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.composition :as composition]))

;; Real content + diff from mixed_format_diff.log
(def mixed-content
  "# Technical Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n\n## Performance\n\nResponse times average **120ms** at p95. The bottleneck is the *serialization layer*.")

(deftest compose-diff-segments-test
  (testing "diff covering entire content -> single :diff-block segment"
    ;; mixed_format: AI replaced the whole document in one Edit call
    (let [anchored [{:type        "diff"
                     :old-text    mixed-content
                     :new-text    "# Architecture Summary\n\nThe system uses **SQLite** for storage.\n\n## Performance\n\nResponse times average **85ms** at p95. The bottleneck is the *serialization layer*."
                     :anchor-status :anchored
                     :char-index  0
                     :length      (count mixed-content)}]
          segments (composition/compose-diff-segments mixed-content anchored)]
      (is (= 1 (count segments)))
      (is (= :diff-block (:type (first segments))))
      (is (= mixed-content (:old-text (first segments))))))

  (testing "diff mid-document -> [:text :diff-block :text]"
    ;; multi_paragraphs: AI changed only the 2nd and 3rd paragraphs
    (let [content  "# Project Overview\n\nThe project builds a collaborative editor with real-time sync.\n\nThe server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol."
          old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
          anchored [{:type          "diff"
                     :old-text      old-text
                     :new-text      "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."
                     :anchor-status :anchored
                     :char-index    (.indexOf content old-text)
                     :length        (count old-text)}]
          segments (composition/compose-diff-segments content anchored)]
      (is (= 3 (count segments)))
      (is (= :text      (:type (first segments))))
      (is (= :diff-block (:type (second segments))))
      (is (= :text      (:type (last segments))))))

  (testing "unmatched and ambiguous diffs are ignored"
    (let [anchored [{:type "diff" :old-text "x" :new-text "y" :anchor-status :unmatched}
                    {:type "diff" :old-text "x" :new-text "y" :anchor-status :ambiguous}]
          segments (composition/compose-diff-segments "Some content." anchored)]
      (is (= 1 (count segments)))
      (is (= :text (:type (first segments))))))

  (testing "multiple diffs sorted by position regardless of input order"
    ;; list_diff: two diffs at different positions in the document
    (let [content  "- Support 100 concurrent users\n- Maintain data consistency\n\nDeployment includes monitoring integration."
          old-a    "- Support 100 concurrent users"
          old-b    "Deployment includes monitoring integration."
          anchored [{:type "diff" :old-text old-b :new-text "Deployment includes observability integration."
                     :anchor-status :anchored :char-index (.indexOf content old-b) :length (count old-b)}
                    {:type "diff" :old-text old-a :new-text "- Support 500 concurrent users"
                     :anchor-status :anchored :char-index 0 :length (count old-a)}]
          segments (composition/compose-diff-segments content anchored)]
      ;; First diff-block should be old-a (position 0), not old-b
      (is (= :diff-block (:type (first segments))))
      (is (= old-a (:old-text (first segments)))))))

(deftest compose-sequential-diffs-test
  (testing "sequential diffs where diff 2 references post-diff-1 state -> both :anchored"
    ;; Real failure from mixed_format_diff: ACP sent two sequential edits.
    ;; Diff 1 renames the title; diff 2's old-text references the already-renamed title.
    ;; compose-sequential-diffs must anchor each diff against the evolving content
    ;; (result of previous diffs applied), so both resolve to :anchored.
    (let [content "# Technical Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n\n## Performance\n\nResponse times average **120ms** at p95. The bottleneck is the *serialization layer*."
          diffs   [{:type "diff"
                    :old-text "# Technical Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n"
                    :new-text "# Architecture Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n"}
                   {:type "diff"
                    :old-text "# Architecture Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n"
                    :new-text "# Architecture Summary\n\nThe system uses **SQLite** for storage.\n"}]
          segments (composition/compose-sequential-diffs content diffs)]
      (is (= 1 (count (filter #(= :diff-block (:type %)) segments))))
      (is (every? #(= :anchored (:anchor-status %))
                  (filter #(= :diff-block (:type %)) segments)))))

  (testing "overlapping sequential diffs merge into one diff-block"
    (let [content  "Hello beautiful wonderful world"
          diffs    [{:type "diff"
                     :old-text "beautiful wonderful"
                     :new-text "amazing"}
                    {:type "diff"
                     :old-text "amazing world"
                     :new-text "great planet"}]
          segments (composition/compose-sequential-diffs content diffs)]
      ;; Two sequential edits to overlapping region -> single merged diff-block
      (is (= 1 (count (filter #(= :diff-block (:type %)) segments))))
      (is (every? #(= :anchored (:anchor-status %))
                  (filter #(= :diff-block (:type %)) segments))))))
