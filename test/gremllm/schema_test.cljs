(ns gremllm.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]))

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

(deftest test-acp-pending-diffs
  (testing "extracts diff items from content"
    (let [update {:content [{:type "diff" :path "/a.md"
                             :old-text "old" :new-text "new"}
                            {:type "text" :text "some output"}
                            {:type "diff" :path "/b.md"
                             :old-text "before" :new-text "after"}]}]
      (is (= [{:type "diff" :path "/a.md" :old-text "old" :new-text "new"}
              {:type "diff" :path "/b.md" :old-text "before" :new-text "after"}]
             (codec/acp-pending-diffs update)))))

  (testing "returns nil when no diff items"
    (is (nil? (codec/acp-pending-diffs {:content [{:type "text" :text "hi"}]}))))

  (testing "returns nil when content is nil"
    (is (nil? (codec/acp-pending-diffs {:content nil}))))

  (testing "returns nil when content is empty"
    (is (nil? (codec/acp-pending-diffs {:content []})))))

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
