(ns gremllm.renderer.ui.topics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.topics :as topics-ui]
            [lookup.core :as lookup]))

(deftest render-left-panel-content-test
  (let [props {:workspace-name        "Work 1"
               :workspace-description "Desc"
               :topics                [{:id "topic-1" :name "Alpha"}
                                       {:id "topic-2" :name nil}]
               :active-topic-id       "topic-2"}
        hiccup (topics-ui/render-left-panel-content props)]

    (testing "New Topic link has correct click handler and label"
      (let [new-link (-> (lookup/select '[nav > ul > li > a] hiccup) first)]
        (is (some? new-link) "A New Topic link should be present.")
        (is (= [[:effects/prevent-default] [:topic.actions/start-new]]
               (-> new-link lookup/attrs (get-in [:on :click])))
            "Clicking New Topic should prevent default and dispatch start-new.")
        (is (= "➕ New Topic" (lookup/text new-link))
            "New Topic link should have the correct label.")))

    (testing "Topics list renders one link per topic with correct handlers"
      (let [all-links   (lookup/select '[nav > ul > li > a] hiccup)
            topic-links (subvec all-links 1)
            [a1 a2]     topic-links]
        (is (= 2 (count topic-links)) "Should render one link per topic.")
        (is (= [[:effects/prevent-default] [:topic.actions/switch-to "topic-1"]]
               (-> a1 lookup/attrs (get-in [:on :click])))
            "First topic link should dispatch switch-to with its id.")
        (is (= [[:effects/prevent-default] [:topic.actions/switch-to "topic-2"]]
               (-> a2 lookup/attrs (get-in [:on :click])))
            "Second topic link should dispatch switch-to with its id.")))

    (testing "Active topic sets aria-current and labels reflect active marker and fallback"
      (let [[a1 a2] (subvec (lookup/select '[nav > ul > li > a] hiccup) 1)]
        (is (nil? (-> a1 lookup/attrs :aria-current))
            "Non-active topic should not set aria-current.")
        (is (= "page" (-> a2 lookup/attrs :aria-current))
            "Active topic should set aria-current to 'page'.")
        (is (= "• Alpha" (lookup/text a1))
            "Non-active topic label should include bullet and the name.")
        (is (= "✓ Untitled" (lookup/text a2))
            "Active topic label should include checkmark and fallback title.")))

    (testing "Workspace header shows name and description"
      (is (= "Work 1" (-> (lookup/select-one 'h4 hiccup) lookup/text))
          "Workspace name should be rendered in an h4.")
      (is (= "Desc" (-> (lookup/select-one 'small hiccup) lookup/text))
          "Workspace description should be rendered inside a small tag."))))
