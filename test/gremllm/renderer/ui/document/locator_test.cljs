(ns gremllm.renderer.ui.document.locator-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.locator :as locator]))

(deftest block-records-test
  (let [markdown "# Title\n\nPara **bold** text\n\n- first\n- second\n\n```clj\n(+ 1 2)\n```\n"
        blocks   (locator/block-records markdown)]
    (testing "extracts block kind, 1-based index, and inclusive 1-based line spans"
      (is (= [{:kind :heading    :index 1 :start-line 1 :end-line 1 :text "Title"}
              {:kind :paragraph  :index 2 :start-line 3 :end-line 3 :text "Para bold text"}
              {:kind :list-item  :index 3 :start-line 5 :end-line 5 :text "first"}
              {:kind :list-item  :index 4 :start-line 6 :end-line 6 :text "second"}
              {:kind :code-block :index 5 :start-line 8 :end-line 10 :text "(+ 1 2)\n"}]
             (mapv #(select-keys % [:kind :index :start-line :end-line :text]) blocks)))))

  (testing "returns an empty vector for blank input"
    (is (= [] (locator/block-records "")))))

(deftest selection-debug-test
  (let [heading-block   {:kind :heading :index 1 :start-line 1 :end-line 1 :text "Title"}
        paragraph-block {:kind :paragraph :index 2 :start-line 3 :end-line 3 :text "Para bold text"}]
    (testing "same-block selections get start/end offsets"
      (is (= {:block-kind  :paragraph
              :block-index 2
              :block-start-line 3
              :block-end-line 3
              :start-offset 5
              :end-offset 14}
             (locator/selection-debug paragraph-block paragraph-block "bold text"))))

    (testing "cross-block selections omit offsets and keep the start block as primary"
      (is (= {:block-kind  :heading
              :block-index 1
              :block-start-line 1
              :block-end-line 1}
             (locator/selection-debug heading-block paragraph-block "Title\nPara"))))

    (testing "blank selected text returns nil"
      (is (nil? (locator/selection-debug paragraph-block paragraph-block ""))))))
