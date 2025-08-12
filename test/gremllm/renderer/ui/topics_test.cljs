(ns gremllm.renderer.ui.topics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.topics :as topics-ui]
            [lookup.core :as lookup]
            [clojure.string :as str]))

(deftest render-left-panel-content-test
  (let [props {:workspace-name        "Work 1"
               :workspace-description "Desc"
               :topics                [{:id "topic-1" :name "Alpha"}
                                       {:id "topic-2" :name nil}]
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
            "Active topic should set aria-current to 'page'.")
        (is (str/includes? (lookup/text active-link) "Untitled")
            "Falls back to 'Untitled' when name is missing.")))

    ))
