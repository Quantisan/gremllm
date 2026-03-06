(ns gremllm.renderer.ui.document.diffs-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.diffs :as diffs]))

;; ---- Anchoring ----
;; "Did we find the right place in the document?"
;; Add cases here when: matching fails on messy real-world content

;; Real document content from multi_paragraphs_diff.log
(def content
  "# Project Overview\n\nThe project builds a collaborative editor with real-time sync.\n\nThe server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol.")

(deftest anchor-test
  (testing "single match"
    ;; Distilled from long_diff.log: AI sent a one-line diff
    (let [diffs [{:type "diff"
                  :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
                  :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."}]
          result (diffs/anchor content diffs)
          r      (first result)]
      (is (= :anchored (:anchor-status r)))
      (is (number? (:char-index r)))
      (is (= (count (:old-text (first diffs))) (:length r)))))

  (testing "multi-paragraph span"
    ;; Real old-text from multi_paragraphs_diff.log: spans two paragraphs
    (let [diffs [{:type "diff"
                  :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol."
                  :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket.\n\nTests cover the CRDT merge logic and sync protocol."}]
          result (diffs/anchor content diffs)]
      (is (= :anchored (:anchor-status (first result))))))

  (testing "no match"
    (let [diffs  [{:type "diff" :old-text "Nonexistent text." :new-text "New."}]
          result (diffs/anchor content diffs)]
      (is (= :unmatched (:anchor-status (first result))))))

  (testing "nil old-text"
    (let [diffs  [{:type "diff" :old-text nil :new-text "New."}]
          result (diffs/anchor content diffs)]
      (is (= :unmatched (:anchor-status (first result))))))

  (testing "ambiguous repeated text"
    (let [repeated "Same line.\n\nSame line."
          diffs    [{:type "diff" :old-text "Same line." :new-text "Changed."}]
          result   (diffs/anchor repeated diffs)]
      (is (= :ambiguous (:anchor-status (first result))))
      (is (= 2 (:match-count (first result))))))

  (testing "multiple diffs at distinct positions"
    ;; Both paragraphs from multi_paragraphs_diff.log, anchored independently
    (let [diffs  [{:type "diff"
                   :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
                   :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."}
                  {:type "diff"
                   :old-text "Tests cover the transformation engine and sync protocol."
                   :new-text "Tests cover the CRDT merge logic and sync protocol."}]
          result (diffs/anchor content diffs)]
      (is (= :anchored (:anchor-status (first result))))
      (is (= :anchored (:anchor-status (second result))))
      (is (not= (:char-index (first result)) (:char-index (second result)))))))

;; ---- Segmentation ----
;; "Given located diffs, did we split content into the right segments?"
;; Add cases here when: segment boundaries are wrong despite correct anchoring

;; Real content + diff from mixed_format_diff.log
(def mixed-content
  "# Technical Summary\n\nThe system uses **PostgreSQL** for storage and **Redis** for caching.\n\n## Performance\n\nResponse times average **120ms** at p95. The bottleneck is the *serialization layer*.")

(deftest compose-segments-test
  (testing "full content replacement"
    ;; mixed_format: AI replaced the whole document in one Edit call
    (let [anchored [{:type        "diff"
                     :old-text    mixed-content
                     :new-text    "# Architecture Summary\n\nThe system uses **SQLite** for storage.\n\n## Performance\n\nResponse times average **85ms** at p95. The bottleneck is the *serialization layer*."
                     :anchor-status :anchored
                     :char-index  0
                     :length      (count mixed-content)}]
          segments (diffs/compose-segments mixed-content anchored)]
      (is (= 1 (count segments)))
      (is (= :diff-block (:type (first segments))))
      (is (= mixed-content (:old-text (first segments))))))

  (testing "mid-document diff"
    ;; multi_paragraphs: AI changed only the 2nd and 3rd paragraphs
    (let [content  "# Project Overview\n\nThe project builds a collaborative editor with real-time sync.\n\nThe server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol."
          old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
          anchored [{:type          "diff"
                     :old-text      old-text
                     :new-text      "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."
                     :anchor-status :anchored
                     :char-index    (.indexOf content old-text)
                     :length        (count old-text)}]
          segments (diffs/compose-segments content anchored)]
      (is (= 3 (count segments)))
      (is (= :text      (:type (first segments))))
      (is (= :diff-block (:type (second segments))))
      (is (= :text      (:type (last segments))))))

  (testing "unmatched/ambiguous ignored"
    (let [anchored [{:type "diff" :old-text "x" :new-text "y" :anchor-status :unmatched}
                    {:type "diff" :old-text "x" :new-text "y" :anchor-status :ambiguous}]
          segments (diffs/compose-segments "Some content." anchored)]
      (is (= 1 (count segments)))
      (is (= :text (:type (first segments))))))

  (testing "multiple diffs sorted by position"
    ;; list_diff: two diffs at different positions in the document
    (let [content  "- Support 100 concurrent users\n- Maintain data consistency\n\nDeployment includes monitoring integration."
          old-a    "- Support 100 concurrent users"
          old-b    "Deployment includes monitoring integration."
          anchored [{:type "diff" :old-text old-b :new-text "Deployment includes observability integration."
                     :anchor-status :anchored :char-index (.indexOf content old-b) :length (count old-b)}
                    {:type "diff" :old-text old-a :new-text "- Support 500 concurrent users"
                     :anchor-status :anchored :char-index 0 :length (count old-a)}]
          segments (diffs/compose-segments content anchored)]
      ;; First diff-block should be old-a (position 0), not old-b
      (is (= :diff-block (:type (first segments))))
      (is (= old-a (:old-text (first segments)))))))

;; ---- Composition (end-to-end) ----
;; "Do independent and dependent diff chains resolve correctly?"
;; Add cases here when: multi-diff interactions produce wrong results

(deftest compose-test
  (testing "two independent diffs"
    (let [content  "AAA\n\nBBB\n\nCCC"
          ds       [{:type "diff" :old-text "AAA" :new-text "XXX"}
                    {:type "diff" :old-text "CCC" :new-text "ZZZ"}]
          segments (diffs/compose content ds)]
      (is (= 3 (count segments)))
      (is (= :diff-block (:type (first segments))))
      (is (= "AAA" (:old-text (first segments))))
      (is (= :text (:type (second segments))))
      (is (= :diff-block (:type (nth segments 2))))
      (is (= "CCC" (:old-text (nth segments 2))))))

  (testing "dependent overlapping diffs merge"
    (let [content     "Hello beautiful wonderful world"
          ds          [{:type "diff" :old-text "beautiful wonderful" :new-text "amazing"}
                       {:type "diff" :old-text "amazing world"      :new-text "great planet"}]
          segments    (diffs/compose content ds)
          diff-blocks (filter #(= :diff-block (:type %)) segments)]
      (is (= 1 (count diff-blocks)))
      (is (= :anchored (:anchor-status (first diff-blocks))))))

  (testing "mixed independent + dependent"
    (let [content     "Title\n\nBody paragraph\n\nFooter"
          ds          [{:type "diff" :old-text "Body paragraph" :new-text "New body"}
                       {:type "diff" :old-text "New body"       :new-text "Final body"}
                       {:type "diff" :old-text "Footer"         :new-text "New footer"}]
          segments    (diffs/compose content ds)
          diff-blocks (filter #(= :diff-block (:type %)) segments)]
      (is (= 2 (count diff-blocks)))
      (is (= "Body paragraph" (:old-text (first diff-blocks))))
      (is (= "Footer" (:old-text (second diff-blocks))))))

  (testing "dependent chain in different regions"
    (let [content     "# Technical Summary\n\nThe system uses PostgreSQL.\n\n## Performance\n\nResponse times average 120ms."
          ds          [{:type "diff"
                        :old-text "# Technical Summary"
                        :new-text "# Architecture Summary"}
                       {:type "diff"
                        :old-text "# Architecture Summary\n\nThe system uses PostgreSQL."
                        :new-text "# Architecture Summary\n\nThe system uses SQLite."}
                       {:type "diff"
                        :old-text "Response times average 120ms."
                        :new-text "Response times average 85ms."}]
          segments    (diffs/compose content ds)
          diff-blocks (filter #(= :diff-block (:type %)) segments)]
      (is (= 2 (count diff-blocks))))))
