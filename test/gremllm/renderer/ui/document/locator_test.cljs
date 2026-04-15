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

(deftest selection-locator-test
  (let [para-block {:kind :paragraph :index 2 :start-line 3 :end-line 3
                    :text "Our Gremllm launched on a Tuesday."}
        heading-block {:kind :heading :index 1 :start-line 1 :end-line 1
                       :text "Gremllm Launch Log"}]
    (testing "same-block selection produces identical start/end BlockRefs, no offsets"
      (let [result (locator/selection-locator para-block para-block "launched on a Tuesday")]
        (is (= "document.md" (:document-relative-path result)))
        (is (= {:kind :paragraph
                :index 2
                :start-line 3
                :end-line 3
                :block-text-snippet "Our Gremllm launched on a Tuesday."}
               (:start-block result)))
        (is (= (:start-block result) (:end-block result)))
        (is (not (contains? result :start-offset)))
        (is (not (contains? result :end-offset)))))

    (testing "cross-block selection"
      (let [result (locator/selection-locator heading-block para-block "Gremllm...Our Gremllm")]
        (is (= {:kind :heading
                :index 1
                :start-line 1
                :end-line 1
                :block-text-snippet "Gremllm Launch Log"}
               (:start-block result)))
        (is (= {:kind :paragraph
                :index 2
                :start-line 3
                :end-line 3
                :block-text-snippet "Our Gremllm launched on a Tuesday."}
               (:end-block result)))))))
