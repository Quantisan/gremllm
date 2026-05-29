(ns gremllm.renderer.ui.document.gutter-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.ui.document.gutter :as gutter]
            [lookup.core :as lookup]))

(def sample-anchor
  {:id "excerpt-1"
   :text "sample text"
   :locator {:document-relative-path "document.md"
             :start-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                           :block-text-snippet "sample text"}
             :end-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                         :block-text-snippet "sample text"}}})

(def sample-topics
  {"topic-1000-a" {:id "topic-1000-a" :name "First" :anchor sample-anchor}
   "topic-2000-b" {:id "topic-2000-b" :name "Second" :anchor sample-anchor}})

(deftest render-gutter-bars-test
  (let [hiccup (gutter/render-gutter sample-topics "topic-1000-a")
        buttons (lookup/select 'button hiccup)]
    (testing "renders a button for each anchored topic"
      (is (= (count sample-topics) (count buttons))))
    (testing "active bar has aria-pressed true"
      (let [active-btn (first buttons)
            attrs (lookup/attrs active-btn)]
        (is (= "true" (:aria-pressed attrs)))))
    (testing "inactive bar has aria-pressed false"
      (let [inactive-btn (second buttons)
            attrs (lookup/attrs inactive-btn)]
        (is (= "false" (:aria-pressed attrs)))))))

(deftest render-empty-gutter-test
  (testing "renders empty gutter when no anchored topics"
    (let [hiccup (gutter/render-gutter {} nil)
          buttons (lookup/select 'button hiccup)]
      (is (empty? buttons)))))
