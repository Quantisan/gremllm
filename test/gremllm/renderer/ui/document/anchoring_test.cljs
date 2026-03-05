(ns gremllm.renderer.ui.document.anchoring-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.anchoring :as anchoring]))

;; Real document content from multi_paragraphs_diff.log
(def content
  "# Project Overview\n\nThe project builds a collaborative editor with real-time sync.\n\nThe server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol.")

(deftest anchor-diffs-test
  (testing "single-sentence match -> :anchored with char position"
    ;; Distilled from long_diff.log: AI sent a one-line diff
    (let [diffs [{:type "diff"
                  :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
                  :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."}]
          result (anchoring/anchor-diffs content diffs)
          r      (first result)]
      (is (= :anchored (:anchor-status r)))
      (is (number? (:char-index r)))
      (is (= (count (:old-text (first diffs))) (:length r)))))

  (testing "multi-paragraph span -> :anchored"
    ;; Real old-text from multi_paragraphs_diff.log: spans two paragraphs
    (let [diffs [{:type "diff"
                  :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket.\n\nTests cover the transformation engine and sync protocol."
                  :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket.\n\nTests cover the CRDT merge logic and sync protocol."}]
          result (anchoring/anchor-diffs content diffs)]
      (is (= :anchored (:anchor-status (first result))))))

  (testing "no match -> :unmatched"
    (let [diffs  [{:type "diff" :old-text "Nonexistent text." :new-text "New."}]
          result (anchoring/anchor-diffs content diffs)]
      (is (= :unmatched (:anchor-status (first result))))))

  (testing "nil old-text -> :unmatched"
    (let [diffs  [{:type "diff" :old-text nil :new-text "New."}]
          result (anchoring/anchor-diffs content diffs)]
      (is (= :unmatched (:anchor-status (first result))))))

  (testing "repeated text -> :ambiguous with match-count"
    (let [repeated "Same line.\n\nSame line."
          diffs    [{:type "diff" :old-text "Same line." :new-text "Changed."}]
          result   (anchoring/anchor-diffs repeated diffs)]
      (is (= :ambiguous (:anchor-status (first result))))
      (is (= 2 (:match-count (first result))))))

  (testing "multiple diffs anchored to distinct positions"
    ;; Both paragraphs from multi_paragraphs_diff.log, anchored independently
    (let [diffs  [{:type "diff"
                   :old-text "The server uses operational transforms for conflict resolution. Clients connect via WebSocket."
                   :new-text "The server uses CRDTs for conflict resolution. Clients connect via WebSocket."}
                  {:type "diff"
                   :old-text "Tests cover the transformation engine and sync protocol."
                   :new-text "Tests cover the CRDT merge logic and sync protocol."}]
          result (anchoring/anchor-diffs content diffs)]
      (is (= :anchored (:anchor-status (first result))))
      (is (= :anchored (:anchor-status (second result))))
      (is (not= (:char-index (first result)) (:char-index (second result)))))))
