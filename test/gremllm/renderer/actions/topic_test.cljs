(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]))

;; Test data
(def test-topic-js
  (clj->js {:id "t1"
            :name "Test Topic"
            :messages [(clj->js {:id "m1" :type "user" :content "Hi"})]}))

(def expected-normalized-topic
  {:id "t1"
   :name "Test Topic"
   :messages [{:id "m1" :type :user :content "Hi"}]})

(def test-topic-clj
  {:id "t1"})

(def denormalized-topic
  {:id "t1"
   :messages [{:id "m1" :type "user"}
              {:id "m2" :type "assistant"}]})

(def expected-normalized-messages
  [{:id "m1" :type :user} {:id "m2" :type :assistant}])

(deftest set-topic-test
  (is (= [[:effects/save topic-state/path expected-normalized-topic]]
         (topic/set-topic {} test-topic-js))
      "should convert JS object, normalize it, and return a save effect")
  (is (nil? (topic/set-topic {} nil))
      "should return nil if input is nil"))

(deftest restore-or-create-topic-test
  (is (= [[:topic.actions/set test-topic-clj]]
         (topic/restore-or-create-topic {} test-topic-clj))
      "should dispatch :set when a topic is provided")

  (is (= [[:topic.actions/start-new]]
         (topic/restore-or-create-topic {} nil))
      "should dispatch :start-new when topic is nil"))

(deftest start-new-topic-test
  (let [new-topic (topic/create-topic)]
    (is (= [[:effects/save topic-state/path new-topic]]
           (topic/start-new-topic {}))
        "should return a save effect with a new topic structure")))

(deftest normalize-topic-test
  (let [normalized (topic/normalize-topic denormalized-topic)]
    (is (= expected-normalized-messages
           (:messages normalized))
        "should convert message types from strings to keywords")))

(deftest create-topic-test
  (let [new-topic (topic/create-topic)]
    (is (= "topic-1" (:id new-topic)) "should have a default id")
    (is (= "New Topic" (:name new-topic)) "should have a default name")
    (is (and (vector? (:messages new-topic))
             (empty? (:messages new-topic)))
        "should have an empty vector for messages")))

(deftest bootstrap-test
  (is (= [[:system.actions/request-info]
          [:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]]
         (topic/bootstrap {}))
      "should request system info and then load the latest topic"))
