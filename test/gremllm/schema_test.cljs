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
  ;; Text chunks produce streaming content updates.
  (doseq [chunk-type (keys test-content-chunks)]
    (testing (str "coerces " (name chunk-type) " from JS with kebab-case keywords")
      (let [content (get test-content-chunks chunk-type)
            js-data #js {:sessionId test-acp-session-id
                         :update #js {:content (clj->js content)
                                      :sessionUpdate (-> chunk-type name (clojure.string/replace #"-" "_"))}}
            result (codec/acp-session-update-from-js js-data)]
        (is (= test-acp-session-id (:acp-session-id result)))

        (when (and (is (contains? (set (keys (:update result))) :session-update))
                   (is (contains? (set (keys (:update result))) :content)))
          (is (= chunk-type (get-in result [:update :session-update])))
          (is (= (:text content) (get-in result [:update :content :text])))))))

  ;; TODO: consider removing this and other non-critical test cases. Tighten overall.
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
            (catch :default _ true)))))

  (testing "coerces tool_call_update with kebab-case keys"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:toolCallId "toolu_abc123"
                                    :status "completed"
                                    :rawOutput "done"
                                    :content #js []
                                    :sessionUpdate "tool_call_update"}}
          result (codec/acp-session-update-from-js js-data)]
      (is (= test-acp-session-id (:acp-session-id result)))
      (is (= :tool-call-update (get-in result [:update :session-update])))
      (is (= "toolu_abc123" (get-in result [:update :tool-call-id])))
      (is (= "completed" (get-in result [:update :status])))))

  (testing "coerces partial tool_call_update payload"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:toolCallId "toolu_abc123"
                                    :sessionUpdate "tool_call_update"}}
          result (codec/acp-session-update-from-js js-data)]
      (is (= :tool-call-update (get-in result [:update :session-update])))
      (is (= "toolu_abc123" (get-in result [:update :tool-call-id])))))

  (testing "coerces available_commands_update with nested arrays and kebab-case keywords"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:availableCommands #js [#js {:name "commit" :description "Create commit"}]
                                    :sessionUpdate "available_commands_update"}}
          result (codec/acp-session-update-from-js js-data)]
      (is (= test-acp-session-id (:acp-session-id result)))

      (when (and (is (contains? (set (keys (:update result))) :session-update))
                 (is (contains? (set (keys (:update result))) :available-commands)))
        (is (= :available-commands-update (get-in result [:update :session-update])))
        (is (= "commit" (get-in result [:update :available-commands 0 :name])))))))

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

(deftest test-acp-tool-call-text
  (testing "returns title and first location path when location exists"
    (let [update {:title "Read File"
                  :locations [{:path "src/gremllm/schema.cljs"}]}]
      (is (= "Read File — src/gremllm/schema.cljs"
             (codec/acp-tool-call-text update)))))

  (testing "returns title when no locations exist"
    (let [update {:title "List Files"
                  :locations []}]
      (is (= "List Files" (codec/acp-tool-call-text update))))))
