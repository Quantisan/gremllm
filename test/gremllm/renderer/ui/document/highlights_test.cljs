(ns gremllm.renderer.ui.document.highlights-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [gremllm.renderer.ui.document.highlights :as h]))

;; Synthetic flat-text index: {:text "..." :spans [[node-ref start end] ...]}
;; node-ref is an opaque id standing in for a real Text node; the pure function
;; must not look inside it. start/end are [start end) offsets in :text.

(defn- span [id s e] [id s e])

(deftest locate-range-single-node-test
  (testing "match inside one text node"
    (let [index {:text  "hello world"
                 :spans [(span :n1 0 11)]}
          r     (h/locate-range-in-flat-text index "world")]
      (is (= {:start-node :n1 :start-offset 6
              :end-node   :n1 :end-offset   11}
             r))))

  (testing "no match returns nil"
    (let [index {:text  "hello world"
                 :spans [(span :n1 0 11)]}]
      (is (nil? (h/locate-range-in-flat-text index "absent"))))))

(deftest locate-range-cross-node-test
  (testing "match spans two adjacent text nodes"
    ;; "Our " in :n1, "Gremllm" in :n2 (strong), " crew" in :n3
    ;; Selected text: "Our Gremllm crew" → crosses all three.
    (let [index {:text  "Our Gremllm crew"
                 :spans [(span :n1 0 4)
                         (span :n2 4 11)
                         (span :n3 11 16)]}
          r     (h/locate-range-in-flat-text index "Our Gremllm crew")]
      (is (= {:start-node :n1 :start-offset 0
              :end-node   :n3 :end-offset   5}
             r))))

  (testing "match ends mid-node"
    ;; Selection: "Our Greml" — ends inside :n2 at local offset 5
    (let [index {:text  "Our Gremllm crew"
                 :spans [(span :n1 0 4)
                         (span :n2 4 11)
                         (span :n3 11 16)]}
          r     (h/locate-range-in-flat-text index "Our Greml")]
      (is (= {:start-node :n1 :start-offset 0
              :end-node   :n2 :end-offset   5}
             r)))))

(deftest locate-range-first-occurrence-test
  (testing "repeated text uses first occurrence"
    (let [index {:text  "the cat and the dog"
                 :spans [(span :n1 0 19)]}
          r     (h/locate-range-in-flat-text index "the")]
      (is (= {:start-node :n1 :start-offset 0
              :end-node   :n1 :end-offset   3}
             r)))))

(deftest locate-range-empty-and-edge-test
  (testing "empty search string returns nil"
    (let [index {:text "hello" :spans [(span :n1 0 5)]}]
      (is (nil? (h/locate-range-in-flat-text index "")))))

  (testing "empty index returns nil"
    (is (nil? (h/locate-range-in-flat-text {:text "" :spans []} "anything")))))

(deftest locate-range-cross-block-test
  ;; Regression tests for cross-block selections (e.g., heading → list item).
  ;; Selection.toString() inserts \n between block elements, but flatten-article
  ;; concatenates text-node values with no separator. These tests currently FAIL
  ;; (return nil) — they document the bug. Fix is a separate slice.

  (testing "selection crosses heading into list item"
    ;; Rendered DOM for "# Title\n\n- Item one":
    ;;   <h1>Title</h1><ul><li>Item one</li></ul>
    ;; TreeWalker(SHOW_TEXT) yields two text nodes: "Title" and "Item one".
    ;; getSelection().toString() across both: "Title\nItem one".
    (let [index {:text  "TitleItem one"
                 :spans [(span :h1-text 0 5)
                         (span :li-text 5 13)]}
          r     (h/locate-range-in-flat-text index "Title\nItem one")]
      (is (= {:start-node :h1-text :start-offset 0
              :end-node   :li-text :end-offset   8}
             r))))

  (testing "selection crosses two paragraphs"
    ;; Rendered DOM for "First para.\n\nSecond para.":
    ;;   <p>First para.</p><p>Second para.</p>
    ;; getSelection().toString() across both: "First para.\n\nSecond para."
    ;; (double newline between paragraph-level blocks).
    (let [index {:text  "First para.Second para."
                 :spans [(span :p1 0 11)
                         (span :p2 11 23)]}
          r     (h/locate-range-in-flat-text index "First para.\n\nSecond para.")]
      (is (= {:start-node :p1 :start-offset 0
              :end-node   :p2 :end-offset   12}
             r)))))
