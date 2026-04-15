(ns gremllm.schema.codec-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema.codec :as codec]
            [gremllm.schema-test :as schema-test]))

(deftest user-message-from-ipc-test
  (testing "coerces a JS-shaped user message payload into schema/Message"
    (let [js-data (clj->js {:id 42
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
          result (codec/user-message-from-ipc js-data)]
      (is (= :user (:type result)))
      (is (= :paragraph (get-in result [:context :excerpts 0 :locator :start-block :kind])))
      (is (= :heading (get-in result [:context :excerpts 0 :locator :end-block :kind])))
      (is (= "launched on a Tuesday"
             (get-in result [:context :excerpts 0 :text]))))))

(deftest test-acp-read-display-label
  (testing "returns 'Read — filename (N lines)' when tool-response meta present"
    (is (= "Read — document.md (37 lines)"
           (codec/acp-read-display-label
             {:session-update :tool-call-update
              :tool-call-id "toolu_01Ext"
              :meta {:claude-code {:tool-name "Read"
                                   :tool-response {:file {:filePath "/path/to/document.md"
                                                          :totalLines 37
                                                          :numLines 37
                                                          :startLine 1}
                                                   :type "text"}}}}))))

  (testing "returns nil when tool-response meta is absent"
    (is (nil? (codec/acp-read-display-label
                {:session-update :tool-call-update
                 :tool-call-id "toolu_01Ext"
                 :status "completed"
                 :meta {:claude-code {:tool-name "Read"}}})))))

;; ACP test fixtures
(def test-acp-session-id "e0eb7ced-4b3f-45af-b911-6b9de025788b")
(def test-content-chunks
  {:agent-message-chunk {:text "Hello" :type "text"}
   :agent-thought-chunk {:text "The user wants" :type "text"}})

(deftest test-acp-session-update-from-js
  (testing "coerces tool_call with kebab-case keys"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:toolCallId "toolu_abc123"
                                    :title "Read File"
                                    :kind "read"
                                    :status "pending"
                                    :rawInput #js {:filePath "src/gremllm/schema.cljs"}
                                    :meta #js {:claudeCode #js {:toolName "Read"}}
                                    :content #js []
                                    :locations #js [#js {:path "src/gremllm/schema.cljs"
                                                         :line 0}]
                                    :sessionUpdate "tool_call"}}
          result (codec/acp-session-update-from-js js-data)]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= :tool-call (get-in result [:update :session-update])))
      (is (= "toolu_abc123" (get-in result [:update :tool-call-id])))
      (is (= "Read File" (get-in result [:update :title])))
      (is (= "src/gremllm/schema.cljs" (get-in result [:update :raw-input :file-path])))
      (is (= "Read" (get-in result [:update :meta :claude-code :tool-name])))
      (is (= "src/gremllm/schema.cljs" (get-in result [:update :locations 0 :path])))
      (is (= 0 (get-in result [:update :locations 0 :line])))))

  (testing "rejects tool_call when location line is not an int"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:toolCallId "toolu_abc123"
                                    :title "Read File"
                                    :kind "read"
                                    :status "pending"
                                    :rawInput #js {:filePath "src/gremllm/schema.cljs"}
                                    :content #js []
                                    :locations #js [#js {:path "src/gremllm/schema.cljs"
                                                         :line "0"}]
                                    :sessionUpdate "tool_call"}}]
      (is (try
            (codec/acp-session-update-from-js js-data)
            false
            (catch :default _ true))))))

(deftest test-acp-session-update-tool-call-update-with-diffs
  (testing "coerces tool_call_update with diff content, locations, and raw output"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call_update"
                                    :toolCallId "toolu_01Lcc"
                                    :status "completed"
                                    :_meta #js {:claudeCode #js {:toolName "mcp__acp__Edit"}}
                                    :content #js [#js {:type "diff"
                                                       :path "/tmp/test.md"
                                                       :oldText "old content"
                                                       :newText "new content"}]
                                    :locations #js [#js {:path "/tmp/test.md" :line 1}]
                                    :rawOutput #js [#js {:type "text"
                                                         :text "--- a\n+++ b\n"}]}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call-update (:session-update update)))
      (is (= "toolu_01Lcc" (:tool-call-id update)))
      (is (= "completed" (:status update)))
      (is (= [{:type "diff" :path "/tmp/test.md"
               :old-text "old content" :new-text "new content"}]
             (:content update)))
      (is (= [{:path "/tmp/test.md" :line 1}] (:locations update)))
      (is (= [{:type "text" :text "--- a\n+++ b\n"}] (:raw-output update)))
      (is (= "mcp__acp__Edit" (get-in update [:meta :claude-code :tool-name])))))

  (testing "coerces tool_call_update without diffs (status-only)"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call_update"
                                    :toolCallId "toolu_02"
                                    :status "completed"}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call-update (:session-update update)))
      (is (= "completed" (:status update)))
      (is (nil? (:content update)))
      (is (nil? (:locations update))))))

(deftest test-acp-session-update-tool-call-edit-kind
  (testing "coerces pending tool_call without locations"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call"
                                    :toolCallId "toolu_04"
                                    :title "Edit File"
                                    :kind "edit"
                                    :status "pending"
                                    :rawInput #js {:filePath "/tmp/test.md"
                                                   :oldString "foo"
                                                   :newString "bar"}
                                    :content #js []}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call (:session-update update)))
      (is (= "edit" (:kind update)))
      (is (nil? (:locations update)))
      (is (= "foo" (get-in update [:raw-input :old-string])))
      (is (= "bar" (get-in update [:raw-input :new-string])))))

  (testing "coerces tool_call_update with string rawOutput"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call_update"
                                    :toolCallId "toolu_05"
                                    :status "failed"
                                    :rawOutput "Error: file not found"}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call-update (:session-update update)))
      (is (= "failed" (:status update)))
      (is (= "Error: file not found" (:raw-output update))))))

(deftest test-acp-session-update-tool-call-wrapped-content
  (testing "coerces tool_call with wrapped text content item"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call"
                                    :toolCallId "toolu_06"
                                    :title "Read File"
                                    :kind "read"
                                    :status "completed"
                                    :rawInput #js {:filePath "src/foo.cljs"}
                                    :content #js [#js {:type "content"
                                                       :content #js {:type "text"
                                                                     :text "file contents"}}]}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call (:session-update update)))
      (is (= [{:type "content" :content {:type "text" :text "file contents"}}]
             (:content update)))))

  (testing "coerces tool_call_update with mixed wrapped and diff content items"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call_update"
                                    :toolCallId "toolu_07"
                                    :status "completed"
                                    :content #js [#js {:type "content"
                                                       :content #js {:type "text"
                                                                     :text "result text"}}
                                                  #js {:type "diff"
                                                       :path "/tmp/foo.md"
                                                       :oldText "old"
                                                       :newText "new"}]}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call-update (:session-update update)))
      (is (= [{:type "content" :content {:type "text" :text "result text"}}
              {:type "diff" :path "/tmp/foo.md" :old-text "old" :new-text "new"}]
             (:content update)))))

  (testing "coerces diff item without oldText (new file creation)"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:sessionUpdate "tool_call_update"
                                    :toolCallId "toolu_08"
                                    :status "completed"
                                    :content #js [#js {:type "diff"
                                                       :path "/tmp/new-file.md"
                                                       :newText "brand new content"}]}}
          result (codec/acp-session-update-from-js js-data)
          update (:update result)]
      (is (= :tool-call-update (:session-update update)))
      (is (= [{:type "diff" :path "/tmp/new-file.md" :new-text "brand new content"}]
             (:content update))))))

(deftest test-tool-response-diffs
  (testing "extracts diff items from PostToolUse tool-call-update"
    (let [update {:session-update :tool-call-update
                  :content [{:type "diff" :path "/a.md"
                             :old-text "old" :new-text "new"}
                            {:type "text" :text "some output"}
                            {:type "diff" :path "/b.md"
                             :old-text "before" :new-text "after"}]}]
      (is (= [{:type "diff" :path "/a.md" :old-text "old" :new-text "new"}
              {:type "diff" :path "/b.md" :old-text "before" :new-text "after"}]
             (codec/tool-response-diffs update)))))

  (testing "returns nil for streaming refinement events (have :kind set)"
    (is (nil? (codec/tool-response-diffs {:session-update :tool-call-update
                                          :kind "edit"
                                          :content [{:type "diff" :path "/a.md"
                                                     :old-text "old" :new-text "new"}]}))))

  (testing "returns nil for :tool-call request events"
    (is (nil? (codec/tool-response-diffs {:session-update :tool-call
                                          :content [{:type "diff" :path "/a.md"
                                                     :old-text "old" :new-text "new"}]}))))

  (testing "returns nil when no diff items"
    (is (nil? (codec/tool-response-diffs {:session-update :tool-call-update
                                          :content [{:type "text" :text "hi"}]}))))

  (testing "returns nil when content is nil"
    (is (nil? (codec/tool-response-diffs {:session-update :tool-call-update
                                          :content nil}))))

  (testing "returns nil when content is empty"
    (is (nil? (codec/tool-response-diffs {:session-update :tool-call-update
                                          :content []})))))

(deftest test-acp-permission-request-from-js
  (testing "coerces tool_name on permission tool call"
    (let [js-data #js {:sessionId test-acp-session-id
                       :toolCall #js {:toolCallId "toolu_perm_01"
                                      :toolName "mcp__acp__Edit"
                                      :kind "edit"
                                      :title "Edit `/tmp/test.md`"
                                      :rawInput #js {:file_path "/tmp/test.md"
                                                     :oldString "before"
                                                     :newString "after"}
                                      :locations #js [#js {:path "/tmp/test.md"}]}
                       :options #js [#js {:optionId "allow"
                                          :name "Allow"
                                          :kind "allow_once"}]}
          result (codec/acp-permission-request-from-js js-data)]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= "toolu_perm_01" (get-in result [:tool-call :tool-call-id])))
      (is (= "mcp__acp__Edit" (get-in result [:tool-call :tool-name])))
      (is (= "edit" (get-in result [:tool-call :kind]))))))

(deftest test-acp-update-text
  ;; Text chunks produce streaming text.
  (doseq [chunk-type (keys test-content-chunks)]
    (testing (str "extracts text from " (name chunk-type) " update")
      (let [content (get test-content-chunks chunk-type)
            update {:session-update chunk-type
                    :content content}]
        (is (= (:text content) (codec/acp-update-text update))))))

  (testing "returns nil for updates without content"
    (let [update {:session-update :available-commands-update
                  :available-commands []}]
      (is (nil? (codec/acp-update-text update))))))

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
