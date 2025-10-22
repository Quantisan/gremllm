(ns gremllm.main.llm-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.llm :as llm]))

(deftest test-provider->env-var-name
  (testing "maps Anthropic to ANTHROPIC_API_KEY"
    (is (= "ANTHROPIC_API_KEY" (llm/provider->env-var-name :anthropic))))

  (testing "maps OpenAI to OPENAI_API_KEY"
    (is (= "OPENAI_API_KEY" (llm/provider->env-var-name :openai))))

  (testing "maps Google to GEMINI_API_KEY"
    (is (= "GEMINI_API_KEY" (llm/provider->env-var-name :google)))))
