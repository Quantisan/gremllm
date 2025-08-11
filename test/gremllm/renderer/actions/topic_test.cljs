(ns gremllm.renderer.actions.topic-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]))

(def ^:private test-topic-id (str "topic-" 54321))
(def ^:private expected-new-topic
  {:id   test-topic-id
   :name "New Topic"
   :messages []})

(deftest create-topic-test
  (with-redefs [topic/generate-topic-id (constantly test-topic-id)]
    (is (= expected-new-topic
           (topic/create-topic))
        "should create a topic with a unique ID and default values")))

(deftest start-new-topic-test
  (with-redefs [topic/create-topic (constantly expected-new-topic)]
    (is (= [[:effects/save (conj topic-state/topics-path (:id expected-new-topic)) expected-new-topic]
            [:effects/save topic-state/active-topic-id-path (:id expected-new-topic)]]
           (topic/start-new-topic {}))
        "should save a new topic and set it as active")))

(deftest set-topic-test
  (testing "when a valid topic is provided"
    (let [raw-topic  (assoc expected-new-topic
                            :messages [{:id "m1" :type "user" :content "Hi"}])
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

(deftest normalize-topic-test
  (let [denormalized (assoc expected-new-topic
                            :messages [{:id "m1" :type "user"}
                                       {:id "m2" :type "assistant"}])
        expected     (assoc expected-new-topic
                            :messages [{:id "m1" :type :user}
                                       {:id "m2" :type :assistant}])]
    (is (= expected (topic/normalize-topic denormalized))
        "should convert message types from strings to keywords")))

(deftest bootstrap-test
  (is (= [[:system.actions/request-info]
          [:topic.effects/list {:on-success [:topic.actions/handle-list]
                                :on-error   [:topic.actions/list-error]}]]
         (topic/bootstrap {}))
      "should request system info and then list topics to decide newest-or-create"))

(deftest handle-list-test
  (testing "when topics exist"
    (is (= [[:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]]
           (topic/handle-list {} (clj->js [{:filename "topic-1.edn" :filepath "/tmp/topic-1.edn"}])))))
  (testing "when no topics exist"
    (is (= [[:topic.actions/start-new]]
           (topic/handle-list {} (clj->js []))))))

(deftest switch-topic-test
  (testing "switching active topic"
    (is (= [[:effects/save topic-state/active-topic-id-path "topic-2"]]
           (topic/switch-topic {} "topic-2"))
        "should dispatch an effect to update the active-topic-id")))
