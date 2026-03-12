(ns gremllm.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
            [malli.core :as m]))

(deftest test-provider->api-key-keyword
  (testing "maps Anthropic to anthropic-api-key"
    (is (= :anthropic-api-key (schema/provider->api-key-keyword :anthropic))))

  (testing "maps OpenAI to openai-api-key"
    (is (= :openai-api-key (schema/provider->api-key-keyword :openai))))

  (testing "maps Google to gemini-api-key"
    (is (= :gemini-api-key (schema/provider->api-key-keyword :google)))))

(deftest test-keyword-to-provider
  (testing "maps anthropic-api-key to :anthropic"
    (is (= :anthropic (schema/keyword-to-provider :anthropic-api-key))))

  (testing "maps openai-api-key to :openai"
    (is (= :openai (schema/keyword-to-provider :openai-api-key))))

  (testing "maps gemini-api-key to :google"
    (is (= :google (schema/keyword-to-provider :gemini-api-key))))

  (testing "throws on unknown storage keyword"
    (is (thrown? js/Error (schema/keyword-to-provider :unknown-api-key)))
    (is (thrown? js/Error (schema/keyword-to-provider :mistral-api-key)))))

(deftest test-model->provider
  (testing "identifies Anthropic models"
    (is (= :anthropic (schema/model->provider "claude-3-5-haiku-latest")))
    (is (= :anthropic (schema/model->provider "claude-3-opus-20240229"))))

  (testing "identifies OpenAI models"
    (is (= :openai (schema/model->provider "gpt-4o")))
    (is (= :openai (schema/model->provider "gpt-4o-mini")))
    (is (= :openai (schema/model->provider "gpt-3.5-turbo"))))

  (testing "identifies Google models"
    (is (= :google (schema/model->provider "gemini-2.0-flash-exp")))
    (is (= :google (schema/model->provider "gemini-pro"))))

  (testing "throws on unknown model prefix"
    (is (thrown? js/Error (schema/model->provider "unknown-model")))
    (is (thrown? js/Error (schema/model->provider "mistral-large")))))

(deftest test-provider-display-name
  (testing "returns display name for Anthropic"
    (is (= "Anthropic" (schema/provider-display-name :anthropic))))

  (testing "returns display name for OpenAI"
    (is (= "OpenAI" (schema/provider-display-name :openai))))

  (testing "returns display name for Google"
    (is (= "Google" (schema/provider-display-name :google)))))

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

;; Fixtures captured from selections on resources/gremllm-launch-log.md

(def single-word-selection
  {:text "Dispatch"
   :is-collapsed false
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 19
   :focus-node "#text"
   :focus-offset 27
   :range {:bounding-rect {:height 33 :left 350.96875 :top 27 :width 117.140625}
           :client-rects  [{:height 33 :left 350.96875 :top 27 :width 117.140625}]
           :common-ancestor "#text"
           :start-container "#text"
           :start-text "Pangalactic Wombat Dispatch"
           :start-offset 19
           :end-container "#text"
           :end-text "Pangalactic Wombat Dispatch"
           :end-offset 27}})

(def mixed-format-selection
  {:text "Our Gremllm crew"
   :is-collapsed false
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 0
   :focus-node "#text"
   :focus-offset 5
   :range {:bounding-rect {:height 17 :left 76 :top 75.5 :width 120.9375}
           :client-rects  [{:height 17 :left 76 :top 75.5 :width 27.640625}
                           {:height 17 :left 103.640625 :top 75.5 :width 58.296875}
                           {:height 17 :left 103.640625 :top 75.5 :width 58.296875}
                           {:height 17 :left 161.9375 :top 75.5 :width 35}]
           :common-ancestor "P"
           :start-container "#text"
           :start-text "Our "
           :start-offset 0
           :end-container "#text"
           :end-text " crew tuned the "
           :end-offset 5}})

(def multi-node-selection
  {:text "Tonight's Wins\nFixed council chat jitter.\nRun npm run test:ci before demos."
   :is-collapsed false
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 0
   :focus-node "#text"
   :focus-offset 14
   :range {:bounding-rect {:height 92.171875 :left 76 :top 130.25 :width 789.796875}
           :client-rects  [{:height 29 :left 76 :top 130.25 :width 168.296875}
                           {:height 21 :left 116 :top 173.421875 :width 749.796875}
                           {:height 17 :left 116 :top 175.421875 :width 152.71875}
                           {:height 17 :left 116 :top 200.171875 :width 28.828125}
                           {:height 24.5 :left 144.828125 :top 197.921875 :width 121.140625}
                           {:height 14 :left 150.078125 :top 203.171875 :width 110.640625}
                           {:height 17 :left 265.96875 :top 200.171875 :width 97.0625}]
           :common-ancestor "DIV"
           :start-container "#text"
           :start-text "Tonight's Wins"
           :start-offset 0
           :end-container "#text"
           :end-text " before demos."
           :end-offset 14}})

(deftest captured-selection-schema-test
  (testing "single word selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection single-word-selection)))

  (testing "mixed format selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection mixed-format-selection)))

  (testing "multi-node selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection multi-node-selection))))

(deftest captured-selection-codec-test
  (testing "captured-selection-from-dom passes through valid data unchanged"
    (is (= single-word-selection (codec/captured-selection-from-dom single-word-selection)))
    (is (= mixed-format-selection (codec/captured-selection-from-dom mixed-format-selection)))
    (is (= multi-node-selection (codec/captured-selection-from-dom multi-node-selection)))))
