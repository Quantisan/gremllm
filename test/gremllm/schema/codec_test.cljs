(ns gremllm.schema.codec-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema.codec :as codec]
            [gremllm.schema-test :as schema-test]))

(deftest user-message-from-ipc-test
  (testing "coerces a JS-shaped user message payload into schema/Message"
    (let [expected-message
          (schema-test/create-message {:id 42
                                       :type :user
                                       :text "reword these"
                                       :context {:excerpts [{:id "e1"
                                                             :text "launched on a Tuesday"
                                                             :locator {:document-relative-path "document.md"
                                                                       :start-block {:kind :paragraph
                                                                                     :index 2
                                                                                     :start-line 3
                                                                                     :end-line 3
                                                                                     :block-text-snippet "Our Gremllm launched on a Tuesday."}
                                                                       :end-block {:kind :heading
                                                                                   :index 1
                                                                                   :start-line 1
                                                                                   :end-line 1
                                                                                   :block-text-snippet "Launch Log"}}}]}})
          js-data (clj->js expected-message)
          result (codec/user-message-from-ipc js-data)]
      (is (= expected-message result)))))

;; ========================================
;; Excerpt (Selection Capture)
;; ========================================

(defn- fixture->js-selection
  "Build a minimal js/Selection-like object from a CapturedSelection fixture.
   Fields and methods match exactly what captured-selection-from-dom reads."
  [{:keys [text range-count anchor-node anchor-offset focus-node focus-offset range]}]
  (let [{:keys [start-container start-text start-offset
                end-container end-text end-offset
                common-ancestor bounding-rect client-rects]} range
        js-range #js {:startContainer          #js {:nodeName    start-container
                                                    :textContent start-text}
                      :startOffset             start-offset
                      :endContainer            #js {:nodeName    end-container
                                                    :textContent end-text}
                      :endOffset               end-offset
                      :commonAncestorContainer #js {:nodeName common-ancestor}
                      :getBoundingClientRect   (constantly (clj->js bounding-rect))
                      :getClientRects          (constantly (clj->js client-rects))}]
    #js {:rangeCount   range-count
         :anchorNode   #js {:nodeName anchor-node}
         :anchorOffset anchor-offset
         :focusNode    #js {:nodeName focus-node}
         :focusOffset  focus-offset
         :toString     (constantly text)
         :getRangeAt   (constantly js-range)}))

(deftest captured-selection-codec-test
  (testing "reads a live selection mock into CapturedSelection shape"
    (is (= schema-test/single-word-selection
           (codec/captured-selection-from-dom (fixture->js-selection schema-test/single-word-selection))))
    (is (= schema-test/mixed-format-selection
           (codec/captured-selection-from-dom (fixture->js-selection schema-test/mixed-format-selection))))
    (is (= schema-test/multi-node-selection
           (codec/captured-selection-from-dom (fixture->js-selection schema-test/multi-node-selection)))))

  (testing "throws when the resulting shape is invalid"
    (let [bad (doto (fixture->js-selection schema-test/single-word-selection)
                (aset "toString" (constantly nil)))]
      (is (try (codec/captured-selection-from-dom bad) false
               (catch :default _ true))))))

(deftest anchor-context-codec-test
  (testing "reads a live panel element mock into AnchorContext shape"
    (let [panel #js {:getBoundingClientRect
                     (constantly #js {:top 100 :left 50 :width 800 :height 600})
                     :scrollTop 20}]
      (is (= {:panel-rect {:top 100 :left 50 :width 800 :height 600}
              :panel-scroll-top 20}
             (codec/anchor-context-from-dom panel))))))

