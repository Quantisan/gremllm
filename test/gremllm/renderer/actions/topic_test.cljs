(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]))

(def ^:private test-timestamp 54321)
(def ^:private expected-new-topic
  {:id (str "topic-" test-timestamp)
   :name "New Topic"
   :messages []})

(deftest set-topic-test
  (testing "when a valid topic is provided"
    (let [raw-topic {:id "t1"
                     :name "Test Topic"
                     :messages [{:id "m1" :type "user" :content "Hi"}]}
          test-topic-js (clj->js raw-topic)
          normalized-topic (topic/normalize-topic raw-topic)]
      (is (= [[:effects/save (conj topic-state/topics-path (:id raw-topic)) normalized-topic]
              [:effects/save topic-state/active-topic-id-path (:id raw-topic)]]
             (topic/set-topic {} test-topic-js))
          "should normalize the topic, save it to the topics map, and set it as active")))

  (testing "when input is nil"
    (is (nil? (topic/set-topic {} nil))
        "should return nil")))

(deftest restore-or-create-topic-test
  (is (= [[:topic.actions/set {:id "t1"}]]
         (topic/restore-or-create-topic {} {:id "t1"}))
      "should dispatch :set when a topic is provided")

  (is (= [[:topic.actions/start-new]]
         (topic/restore-or-create-topic {} nil))
      "should dispatch :start-new when topic is nil"))

(deftest start-new-topic-test
  (with-redefs [topic/create-topic (constantly expected-new-topic)]
    (is (= [[:effects/save (conj topic-state/topics-path (:id expected-new-topic)) expected-new-topic]
            [:effects/save topic-state/active-topic-id-path (:id expected-new-topic)]]
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
  (with-redefs [topic/get-timestamp (constantly test-timestamp)]
    (is (= expected-new-topic
           (topic/create-topic))
        "should create a topic with a unique ID and default values")))

(deftest bootstrap-test
  (is (= [[:system.actions/request-info]
          [:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]]
         (topic/bootstrap {}))
      "should request system info and then load the latest topic"))
