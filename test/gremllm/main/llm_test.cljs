(ns gremllm.main.llm-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.llm :as llm]))

(deftest test-model->provider
  (testing "identifies Anthropic models"
    (is (= :anthropic (llm/model->provider "claude-3-5-haiku-latest")))
    (is (= :anthropic (llm/model->provider "claude-3-opus-20240229"))))

  (testing "identifies OpenAI models"
    (is (= :openai (llm/model->provider "gpt-4o")))
    (is (= :openai (llm/model->provider "gpt-4o-mini")))
    (is (= :openai (llm/model->provider "gpt-3.5-turbo"))))

  (testing "identifies Google models"
    (is (= :google (llm/model->provider "gemini-2.0-flash-exp")))
    (is (= :google (llm/model->provider "gemini-pro"))))

  (testing "throws on unknown model prefix"
    (is (thrown? js/Error (llm/model->provider "unknown-model")))
    (is (thrown? js/Error (llm/model->provider "mistral-large")))))

(deftest test-provider->api-key-keyword
  (testing "maps Anthropic to anthropic-api-key"
    (is (= :anthropic-api-key (llm/provider->api-key-keyword :anthropic))))

  (testing "maps OpenAI to openai-api-key"
    (is (= :openai-api-key (llm/provider->api-key-keyword :openai))))

  (testing "maps Google to gemini-api-key"
    (is (= :gemini-api-key (llm/provider->api-key-keyword :google)))))

(deftest test-provider->env-var-name
  (testing "maps Anthropic to ANTHROPIC_API_KEY"
    (is (= "ANTHROPIC_API_KEY" (llm/provider->env-var-name :anthropic))))

  (testing "maps OpenAI to OPENAI_API_KEY"
    (is (= "OPENAI_API_KEY" (llm/provider->env-var-name :openai))))

  (testing "maps Google to GEMINI_API_KEY"
    (is (= "GEMINI_API_KEY" (llm/provider->env-var-name :google)))))
