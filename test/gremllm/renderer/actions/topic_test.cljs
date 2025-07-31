(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]))

(deftest set-topic-test
  (let [test-topic-js (clj->js {:id "t1"
                                :name "Test Topic"
                                :messages [(clj->js {:id "m1" :type "user" :content "Hi"})]})
        expected-normalized-topic {:id "t1"
                                   :name "Test Topic"
                                   :messages [{:id "m1" :type :user :content "Hi"}]}]
    (is (= [[:effects/save (conj topic-state/topics-path "t1") expected-normalized-topic]
            [:effects/save topic-state/active-topic-id-path "t1"]]
           (topic/set-topic {} test-topic-js))
        "should normalize the topic and save it to the topics map, and set it as active"))

  (is (nil? (topic/set-topic {} nil))
      "should return nil if input is nil"))

(deftest restore-or-create-topic-test
  (is (= [[:topic.actions/set {:id "t1"}]]
         (topic/restore-or-create-topic {} {:id "t1"}))
      "should dispatch :set when a topic is provided")

  (is (= [[:topic.actions/start-new]]
         (topic/restore-or-create-topic {} nil))
      "should dispatch :start-new when topic is nil"))

(deftest start-new-topic-test
  (let [new-topic (topic/create-topic)]
    (is (= [[:effects/save (conj topic-state/topics-path (:id new-topic)) new-topic]
            [:effects/save topic-state/active-topic-id-path (:id new-topic)]]
           (topic/start-new-topic {}))
        "should save a new topic and set it as active")))

(deftest normalize-topic-test
  (let [denormalized {:id "t1"
                      :name "String Types"
                      :messages [{:id "m1" :type "user"}
                                 {:id "m2" :type "assistant"}]}
        expected {:id "t1"
                  :name "String Types"
                  :messages [{:id "m1" :type :user}
                             {:id "m2" :type :assistant}]}]
    (is (= expected (topic/normalize-topic denormalized))
        "should convert message types from strings to keywords")))

(deftest create-topic-test
  (is (= {:id "topic-1"
          :name "New Topic"
          :messages []}
         (topic/create-topic))
      "should create a topic with default values"))

(deftest bootstrap-test
  (is (= [[:system.actions/request-info]
          [:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]]
         (topic/bootstrap {}))
      "should request system info and then load the latest topic"))
