(ns gremllm.renderer.state.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.state.session :as session-state]))

(def stub-anchor {:id "e1" :text "x" :locator {}})

(deftest color-for-topic-test
  (let [topics-map {"topic-1000-a" {:id "topic-1000-a" :name "T1" :anchor stub-anchor}
                    "topic-2000-b" {:id "topic-2000-b" :name "T2"}}]
    (testing "an anchored session gets one of the palette colors"
      (is (some #{(session-state/color-for-topic topics-map "topic-1000-a")}
                session-state/session-colors)))
    (testing "an unanchored session has no color"
      (is (nil? (session-state/color-for-topic topics-map "topic-2000-b"))))
    (testing "an unknown id has no color"
      (is (nil? (session-state/color-for-topic topics-map "topic-9999-z"))))))

(deftest color-stable-across-set-changes-test
  ;; Color is keyed off the stable topic id, not the session's position in the
  ;; live anchored list -- so a bar keeps its color as other sessions come and go.
  (let [alone     {"topic-2000-b" {:id "topic-2000-b" :anchor stub-anchor}}
        surrounded {"topic-1000-a" {:id "topic-1000-a" :anchor stub-anchor}
                    "topic-2000-b" {:id "topic-2000-b" :anchor stub-anchor}
                    "topic-3000-c" {:id "topic-3000-c" :anchor stub-anchor}}]
    (testing "same id yields the same color regardless of which other sessions exist"
      (is (= (session-state/color-for-topic alone "topic-2000-b")
             (session-state/color-for-topic surrounded "topic-2000-b"))))))

(deftest anchored-topics-test
  (testing "with anchored topics present"
    ;; Fixture deliberately includes an un-anchored "topic-1000-a": one assertion
    ;; proves both that unanchored topics are filtered out AND that the rest sort
    ;; by :id ascending (== creation order; see anchored-topics-sorted).
    (let [topics-map {"topic-3000-c" {:id "topic-3000-c" :anchor {:id "e1" :text "x" :locator {}}}
                      "topic-1000-a" {:id "topic-1000-a"}
                      "topic-2000-b" {:id "topic-2000-b" :anchor {:id "e2" :text "y" :locator {}}}}]
      (testing "returns only anchored topics sorted by id ascending"
        (is (= ["topic-2000-b" "topic-3000-c"]
               (mapv :id (session-state/anchored-topics-sorted topics-map)))))
      (testing "most-recent-anchored returns last by id descending"
        (is (= "topic-3000-c"
               (:id (session-state/most-recent-anchored topics-map)))))))

  (testing "with no anchored topics"
    (let [topics-map {"topic-1000-a" {:id "topic-1000-a"}}]
      (testing "returns empty"
        (is (empty? (session-state/anchored-topics-sorted topics-map))))
      (testing "most-recent-anchored returns nil"
        (is (nil? (session-state/most-recent-anchored topics-map)))))))
