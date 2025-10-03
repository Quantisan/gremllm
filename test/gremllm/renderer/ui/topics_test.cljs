(ns gremllm.renderer.ui.topics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.topics :as topics-ui]
            [lookup.core :as lookup]))

(deftest render-left-panel-content-test
  (let [props {:workspace             {:name "Work 1" :description "Desc"}
               :topics-map            {"topic-1" {:id "topic-1" :name "Alpha"}
                                       "topic-2" {:id "topic-2" :name "Beta"}}
               :active-topic-id       "topic-2"}
        hiccup (topics-ui/render-left-panel-content props)]

    (testing "New Topic link dispatches start-new"
      (let [new-link (-> (lookup/select '[nav > ul > li > a] hiccup) first)]
        (is (= [[:effects/prevent-default] [:topic.actions/start-new]]
               (-> new-link lookup/attrs (get-in [:on :click])))
            "Clicking New Topic should prevent default and dispatch start-new.")))

    (testing "Topic links dispatch switch-to with their ids"
      (let [topic-links  (-> (lookup/select '[nav > ul > li > a] hiccup) rest)
            expected-ids (map :id (:topics props))]
        (doseq [[expected-id a] (map vector expected-ids topic-links)]
          (is (= expected-id
                 (-> a lookup/attrs (get-in [:on :click 1 1])))
              "Each link dispatches switch-to with its id."))))

    (testing "Active topic sets aria-current and falls back to 'Untitled'"
      (let [active-link (lookup/select-one '"a[aria-current=page]" hiccup)]
        (is (= "page" (-> active-link lookup/attrs :aria-current))
            "Active topic should set aria-current to 'page'.")))))

(deftest unsaved-and-active-markers-test
  (let [props  {:workspace       {:name "Work"}
                :topics-map      {"t1" {:id "t1" :name "Clean"}
                                  "t2" {:id "t2" :name "Dirty" :unsaved? true}}
                :active-topic-id "t2"}
        hiccup (topics-ui/render-left-panel-content props)
        links  (->> hiccup (lookup/select '[nav > ul > li > a]) rest)]
    (is (= ["• Clean" "✓ Dirty *"] (map lookup/text links))
        "Active topics show ✓, inactive show •, unsaved append *")))

(deftest rename-mode-input-test
  (let [props  {:workspace         {:name "Work"}
                :topics-map        {"t1" {:id "t1" :name "Alpha"}}
                :renaming-topic-id "t1"}
        hiccup (topics-ui/render-left-panel-content props)
        input  (lookup/select-one '[nav > ul > li > input] hiccup)]
    (is (= "Alpha" (-> input lookup/attrs :default-value))
        "Rename mode renders input with topic name as default-value")))

(deftest double-click-rename-action-test
  (let [props  {:workspace    {:name "Work"}
                :topics-map   {"t1" {:id "t1" :name "Alpha"}}}
        hiccup (topics-ui/render-left-panel-content props)
        link   (->> hiccup (lookup/select '[nav > ul > li > a]) rest first)]
    (is (= [:topic.actions/begin-rename "t1"]
           (-> link lookup/attrs (get-in [:on :dblclick 1])))
        "Double-click dispatches begin-rename action with topic ID")))


