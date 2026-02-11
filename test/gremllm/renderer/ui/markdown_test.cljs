(ns gremllm.renderer.ui.markdown-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [gremllm.renderer.ui.markdown :as md]))

(deftest markdown->html-test
  (testing "renders basic markdown"
    (let [result (md/markdown->html "**bold** and `code`")]
      (is (str/includes? result "<strong>bold</strong>"))
      (is (str/includes? result "<code>code</code>"))))

  (testing "sanitizes script tags"
    (let [result (md/markdown->html "<script>alert('xss')</script>")]
      (is (not (str/includes? result "<script")))
      (is (not (str/includes? result "alert")))))

  (testing "sanitizes javascript: URLs"
    (let [result (md/markdown->html "[bad link](javascript:alert('xss'))")]
      (is (not (str/includes? result "javascript:")))
      (is (not (str/includes? result "alert")))))

  (testing "sanitizes event handlers"
    (let [result (md/markdown->html "<a onclick=\"alert('xss')\">click</a>")]
      (is (not (str/includes? result "onclick")))))

  (testing "allows safe links"
    (let [result (md/markdown->html "[safe](https://example.com)")]
      (is (str/includes? result "https://example.com")))))
