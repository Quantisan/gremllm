(ns gremllm.renderer.state.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.state.session :as session-state]))

(def stub-anchor {:id "e1" :text "x" :locator {}})

(deftest color-for-topic-test
  (let [topics-map {"topic-1000-a" {:id "topic-1000-a" :name "T1" :anchor stub-anchor}
                    "topic-2000-b" {:id "topic-2000-b" :name "T2" :anchor stub-anchor}
                    "topic-3000-c" {:id "topic-3000-c" :name "T3" :anchor stub-anchor}}]
    (testing "first topic gets color 1"
      (is (= "var(--session-color-1)" (session-state/color-for-topic topics-map "topic-1000-a"))))
    (testing "second topic gets color 2"
      (is (= "var(--session-color-2)" (session-state/color-for-topic topics-map "topic-2000-b"))))
    (testing "third topic gets color 3"
      (is (= "var(--session-color-3)" (session-state/color-for-topic topics-map "topic-3000-c"))))))

(deftest color-wraps-modulo-5-test
  (let [topics-map (into {} (map-indexed
                              (fn [i _] [(str "topic-" (* (inc i) 1000) "-x")
                                         {:id (str "topic-" (* (inc i) 1000) "-x")
                                          :anchor stub-anchor}])
                              (range 7)))]
    (testing "6th topic wraps to color 1"
      (let [sorted-ids (sort (keys topics-map))]
        (is (= (session-state/color-for-topic topics-map (nth sorted-ids 5))
               (session-state/color-for-topic topics-map (nth sorted-ids 0))))))))

(deftest shell?-test
  (testing "anchored topic with no ACP session id is a shell"
    (is (true? (session-state/shell? {:id "t1" :anchor stub-anchor :session {}})))
    (is (true? (session-state/shell? {:id "t1" :anchor stub-anchor}))))
  (testing "topic with a live ACP session id is not a shell"
    (is (false? (session-state/shell? {:id "t1" :anchor stub-anchor :session {:id "s1"}})))))

(deftest anchored-topics-sorted-test
  (let [topics-map {"topic-3000-c" {:id "topic-3000-c" :anchor {:id "e1" :text "x" :locator {}}}
                    "topic-1000-a" {:id "topic-1000-a"}
                    "topic-2000-b" {:id "topic-2000-b" :anchor {:id "e2" :text "y" :locator {}}}}]
    (testing "returns only anchored topics sorted by id ascending"
      (is (= ["topic-2000-b" "topic-3000-c"]
             (mapv :id (session-state/anchored-topics-sorted topics-map)))))
    (testing "most-recent-anchored returns last by id descending"
      (is (= "topic-3000-c"
             (:id (session-state/most-recent-anchored topics-map)))))))

(deftest no-anchored-topics-test
  (let [topics-map {"topic-1000-a" {:id "topic-1000-a"}}]
    (testing "returns empty when no anchored topics"
      (is (empty? (session-state/anchored-topics-sorted topics-map))))
    (testing "most-recent-anchored returns nil"
      (is (nil? (session-state/most-recent-anchored topics-map))))))
