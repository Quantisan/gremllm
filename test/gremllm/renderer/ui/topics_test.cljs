(ns gremllm.renderer.ui.topics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.topics :as topics-ui]
            [lookup.core :as lookup]))

(deftest unsaved-and-active-markers-test
  (let [props  {:document-meta       {:name "Work"}
                :topics-map      {"t1" {:id "t1" :name "Clean"}
                                  "t2" {:id "t2" :name "Dirty" :unsaved? true}}
                :active-topic-id "t2"}
        hiccup (topics-ui/render-left-panel-content props)
        links  (->> hiccup (lookup/select '[nav > ul > li a]) rest)]
    (is (= ["• Clean" "✓ Dirty *"] (map lookup/text links))
        "Active topics show ✓, inactive show •, unsaved append *")))

(deftest rename-mode-input-test
  (let [props  {:document-meta         {:name "Work"}
                :topics-map        {"t1" {:id "t1" :name "Alpha"}}
                :renaming-topic-id "t1"}
        hiccup (topics-ui/render-left-panel-content props)
        input  (lookup/select-one '[nav > ul > li > input] hiccup)]
    (is (= "Alpha" (-> input lookup/attrs :default-value))
        "Rename mode renders input with topic name as default-value")))

(deftest double-click-rename-action-test
  (let [props  {:document-meta    {:name "Work"}
                :topics-map   {"t1" {:id "t1" :name "Alpha"}}}
        hiccup (topics-ui/render-left-panel-content props)
        link   (->> hiccup (lookup/select '[nav > ul > li a]) rest first)]
    (is (= [:topic.actions/begin-rename "t1"]
           (-> link lookup/attrs (get-in [:on :dblclick 1])))
        "Double-click dispatches begin-rename action with topic ID")))


