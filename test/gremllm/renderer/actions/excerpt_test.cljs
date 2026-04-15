(ns gremllm.renderer.actions.excerpt-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.schema-test :as schema-test]))

;; Anchor context fixture matching AnchorContext schema
(def anchor-context
  {:panel-rect {:top 100 :left 50 :width 800 :height 600}
   :panel-scroll-top 20})

(def locator-hints
  {:document-relative-path "document.md"
   :start-block {:kind :paragraph
                 :index 2
                 :start-line 3
                 :end-line 3
                 :block-text-snippet "Our Gremllm launched on a Tuesday."}
   :end-block {:kind :paragraph
               :index 2
               :start-line 3
               :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}
   :start-offset 5
   :end-offset 14})

;; Composite input that the :event/text-selection placeholder produces,
;; with both sides already coerced at the codec boundary.
(def composite-selection
  {:selection schema-test/single-word-selection
   :anchor anchor-context
   :locator-hints locator-hints})

;; ========================================
;; capture
;; ========================================

(deftest capture-test
  (testing "nil composite - dispatches dismiss-popover"
    (let [result (excerpt/capture {} nil)]
      (is (= [[:excerpt.actions/dismiss-popover]] result))))

  (testing "nil locator-hints - dispatches dismiss-popover"
    (let [result (excerpt/capture {} {:selection schema-test/single-word-selection
                                      :anchor anchor-context
                                      :locator-hints nil})]
      (is (= [[:excerpt.actions/dismiss-popover]] result))))

  (testing "valid composite saves selection, anchor, and locator hints"
    (let [result (excerpt/capture {} composite-selection)]
      (is (= [:effects/save excerpt-state/captured-path schema-test/single-word-selection]
             (nth result 0)))
      (is (= [:effects/save excerpt-state/anchor-path anchor-context]
             (nth result 1)))
      (is (= [:effects/save excerpt-state/locator-hints-path locator-hints]
             (nth result 2))))))

;; ========================================
;; dismiss-popover
;; ========================================

(deftest dismiss-popover-test
  (testing "clears captured-path, anchor-path, and locator-hints-path"
    (is (= [[:effects/save excerpt-state/captured-path nil]
            [:effects/save excerpt-state/anchor-path nil]
            [:effects/save excerpt-state/locator-hints-path nil]]
           (excerpt/dismiss-popover {})))))

(deftest capture->excerpt-same-block-test
  (testing "builds DocumentExcerpt from captured text and same-block locator-hints"
    (let [captured {:text "launched on a Tuesday"
                    :range-count 1
                    :anchor-node "#text"
                    :anchor-offset 4
                    :focus-node "#text"
                    :focus-offset 25
                    :range {}}
          hints locator-hints
          result (excerpt/capture->excerpt captured hints "abc-123")]
      (is (= "abc-123" (:id result)))
      (is (= "launched on a Tuesday" (:text result)))
      (is (= hints (:locator result))))))

(deftest capture->excerpt-cross-block-test
  (testing "cross-block excerpt has no offsets"
    (let [captured {:text "Gremllm...Our Gremllm"}
          hints {:document-relative-path "document.md"
                 :start-block {:kind :heading
                               :index 1
                               :start-line 1
                               :end-line 1
                               :block-text-snippet "Gremllm Launch Log"}
                 :end-block {:kind :paragraph
                             :index 2
                             :start-line 3
                             :end-line 3
                             :block-text-snippet "Our Gremllm..."}}
          result (excerpt/capture->excerpt captured hints "xyz")]
      (is (not (contains? (:locator result) :start-offset))))))

(deftest add-builds-and-persists-document-excerpt-test
  (testing "add reads captured excerpt data, appends it to topic excerpts, and dismisses the popover"
    (let [topic-id "t1"
          state {:active-topic-id topic-id
                 :topics {topic-id {:id topic-id :messages [] :excerpts []}}
                 :excerpt {:captured {:text "hello"}
                           :locator-hints {:document-relative-path "document.md"
                                           :start-block {:kind :paragraph
                                                         :index 2
                                                         :start-line 3
                                                         :end-line 3
                                                         :block-text-snippet "hello world"}
                                           :end-block {:kind :paragraph
                                                       :index 2
                                                       :start-line 3
                                                       :end-line 3
                                                       :block-text-snippet "hello world"}
                                           :start-offset 0
                                           :end-offset 5}}}
          result (excerpt/add state)
          [save-action mark-unsaved-action auto-save-action dismiss-action] result
          [_ save-path excerpts] save-action
          [saved-excerpt] excerpts]
      (is (= :effects/save (first save-action)))
      (is (= (topic-state/excerpts-path topic-id) save-path))
      (is (= "hello" (:text saved-excerpt)))
      (is (string? (:id saved-excerpt)))
      (is (= {:document-relative-path "document.md"
              :start-block {:kind :paragraph
                            :index 2
                            :start-line 3
                            :end-line 3
                            :block-text-snippet "hello world"}
              :end-block {:kind :paragraph
                          :index 2
                          :start-line 3
                          :end-line 3
                          :block-text-snippet "hello world"}
              :start-offset 0
              :end-offset 5}
             (:locator saved-excerpt)))
      (is (= [:topic.actions/mark-active-unsaved] mark-unsaved-action))
      (is (= [:topic.effects/auto-save topic-id] auto-save-action))
      (is (= [:excerpt.actions/dismiss-popover] dismiss-action)))))

(deftest add-without-capture-is-noop-test
  (is (nil? (excerpt/add {}))))
