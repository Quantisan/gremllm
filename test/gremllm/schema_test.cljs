(ns gremllm.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema :as schema]))

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

(deftest test-models-by-provider
  (testing "groups models by provider"
    (let [grouped (schema/models-by-provider)]
      (is (map? grouped))
      (is (contains? grouped "Anthropic"))
      (is (contains? grouped "OpenAI"))
      (is (contains? grouped "Google"))

      ;; Check that all models are accounted for
      (is (= (set (keys schema/supported-models))
             (set (apply concat (vals grouped))))))))

(deftest test-attachment-ref->api-format
  (testing "transforms valid attachment-ref and buffer to API format"
    (let [attachment-ref {:ref "abc12345"
                          :name "test.png"
                          :mime-type "image/png"
                          :size 1024}
          buffer (js/Buffer.from "test-content" "utf-8")
          result (schema/attachment-ref->api-format attachment-ref buffer)]
      (is (= "image/png" (:mime-type result)))
      (is (= (.toString buffer "base64") (:data result)))))

  (testing "validates mime-type is present"
    (let [attachment-ref {:ref "abc12345"
                          :name "test.png"
                          :size 1024}
          buffer (js/Buffer.from "test-content" "utf-8")]
      (is (thrown? js/Error (schema/attachment-ref->api-format attachment-ref buffer)))))

  (testing "validates data is a string"
    (let [attachment-ref {:ref "abc12345"
                          :name "test.png"
                          :mime-type "image/png"
                          :size 1024}]
      (is (thrown? js/Error (schema/attachment-ref->api-format attachment-ref nil))))))

(deftest test-messages->chat-api-format
  (testing "converts internal message format to Chat API format"
    (is (= [{:role "user" :content "Hello"}
            {:role "assistant" :content "Hi there"}]
           (schema/messages->chat-api-format
            [{:type :user :text "Hello"}
             {:type :assistant :text "Hi there"}])))))

(deftest test-acp-session-update-from-js
  (testing "coerces agent_message_chunk from JS with kebab-case keywords"
    (let [js-data #js {:sessionId "e0eb7ced-4b3f-45af-b911-6b9de025788b"
                       :update #js {:content #js {:text "Hello" :type "text"}
                                    :sessionUpdate "agent_message_chunk"}}
          result (schema/acp-session-update-from-js js-data)]
      (is (= "e0eb7ced-4b3f-45af-b911-6b9de025788b" (:session-id result)))

      (when (and (is (contains? (set (keys (:update result))) :session-update))
                 (is (contains? (set (keys (:update result))) :content)))
        (is (= "agent_message_chunk" (get-in result [:update :session-update])))
        (is (= "Hello" (get-in result [:update :content :text]))))))

  (testing "coerces agent_thought_chunk from JS with kebab-case keywords"
    (let [js-data #js {:sessionId "abc-123"
                       :update #js {:content #js {:text "The user wants" :type "text"}
                                    :sessionUpdate "agent_thought_chunk"}}
          result (schema/acp-session-update-from-js js-data)]
      (is (= "abc-123" (:session-id result)))

      (when (is (contains? (set (keys (:update result))) :session-update))
        (is (= "agent_thought_chunk" (get-in result [:update :session-update]))))))

  (testing "coerces available_commands_update with nested arrays and kebab-case keywords"
    (let [js-data #js {:sessionId "xyz-789"
                       :update #js {:availableCommands #js [#js {:name "commit" :description "Create commit"}]
                                    :sessionUpdate "available_commands_update"}}
          result (schema/acp-session-update-from-js js-data)]
      (is (= "xyz-789" (:session-id result)))

      (when (and (is (contains? (set (keys (:update result))) :session-update))
                 (is (contains? (set (keys (:update result))) :available-commands)))
        (is (= "available_commands_update" (get-in result [:update :session-update])))
        (is (= "commit" (get-in result [:update :available-commands 0 :name])))))))
