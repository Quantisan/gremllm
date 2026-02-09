(ns gremllm.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string]
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

;; ACP test fixtures
(def test-acp-session-id "e0eb7ced-4b3f-45af-b911-6b9de025788b")
(def test-content-chunks
  {:agent-message-chunk {:text "Hello" :type "text"}
   :agent-thought-chunk {:text "The user wants" :type "text"}})

(deftest test-acp-session-update-from-js
  ;; Test content chunks derived from schema/acp-chunk->message-type
  (doseq [chunk-type (keys schema/acp-chunk->message-type)]
    (testing (str "coerces " (name chunk-type) " from JS with kebab-case keywords")
      (let [content (get test-content-chunks chunk-type)
            js-data #js {:sessionId test-acp-session-id
                         :update #js {:content (clj->js content)
                                      :sessionUpdate (-> chunk-type name (clojure.string/replace #"-" "_"))}}
            result (schema/acp-session-update-from-js js-data)]
        (is (= test-acp-session-id (:acp-session-id result)))

        (when (and (is (contains? (set (keys (:update result))) :session-update))
                   (is (contains? (set (keys (:update result))) :content)))
          (is (= chunk-type (get-in result [:update :session-update])))
          (is (= (:text content) (get-in result [:update :content :text])))))))

  (testing "coerces available_commands_update with nested arrays and kebab-case keywords"
    (let [js-data #js {:sessionId test-acp-session-id
                       :update #js {:availableCommands #js [#js {:name "commit" :description "Create commit"}]
                                    :sessionUpdate "available_commands_update"}}
          result (schema/acp-session-update-from-js js-data)]
      (is (= test-acp-session-id (:acp-session-id result)))

      (when (and (is (contains? (set (keys (:update result))) :session-update))
                 (is (contains? (set (keys (:update result))) :available-commands)))
        (is (= :available-commands-update (get-in result [:update :session-update])))
        (is (= "commit" (get-in result [:update :available-commands 0 :name])))))))

(deftest test-acp-update-text
  ;; Test content chunks derived from schema/acp-chunk->message-type
  (doseq [chunk-type (keys schema/acp-chunk->message-type)]
    (testing (str "extracts text from " (name chunk-type) " update")
      (let [content (get test-content-chunks chunk-type)
            update {:session-update chunk-type
                    :content content}]
        (is (= (:text content) (schema/acp-update-text update))))))

  (testing "returns nil for updates without content"
    (let [update {:session-update :available-commands-update
                  :available-commands []}]
      (is (nil? (schema/acp-update-text update))))))
