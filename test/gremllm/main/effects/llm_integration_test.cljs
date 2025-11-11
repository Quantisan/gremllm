(ns gremllm.main.effects.llm-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]))

;; Integration Tests
;; These call real APIs to validate end-to-end functionality including response normalization.
;; Run with: ANTHROPIC_API_KEY=... OPENAI_API_KEY=... GEMINI_API_KEY=... npm run test:integration

(deftest test-query-llm-provider-anthropic-integration
  (async done
    (testing "INTEGRATION: validate Anthropic API returns normalized response"
      (let [api-key (.-ANTHROPIC_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Anthropic integration test - ANTHROPIC_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "claude-3-5-haiku-latest" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== ANTHROPIC API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches normalized LLMResponse schema"
                           ;; Validate normalized structure
                           (is (string? (:text response)) "text should be a string")
                           (is (seq (:text response)) "text should be non-empty")

                           ;; Validate usage map
                           (is (map? (:usage response)) "usage should be a map")
                           (is (int? (:input-tokens (:usage response))) "input-tokens should be an int")
                           (is (int? (:output-tokens (:usage response))) "output-tokens should be an int")
                           (is (int? (:total-tokens (:usage response))) "total-tokens should be an int")
                           (is (pos? (:input-tokens (:usage response))) "input-tokens should be positive")
                           (is (pos? (:output-tokens (:usage response))) "output-tokens should be positive")
                           (is (pos? (:total-tokens (:usage response))) "total-tokens should be positive"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-query-llm-provider-openai-integration
  (async done
    (testing "INTEGRATION: validate OpenAI API returns normalized response"
      (let [api-key (.-OPENAI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping OpenAI integration test - OPENAI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "gpt-4o-mini" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== OPENAI API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches normalized LLMResponse schema"
                           ;; Validate normalized structure
                           (is (string? (:text response)) "text should be a string")
                           (is (seq (:text response)) "text should be non-empty")

                           ;; Validate usage map
                           (is (map? (:usage response)) "usage should be a map")
                           (is (int? (:input-tokens (:usage response))) "input-tokens should be an int")
                           (is (int? (:output-tokens (:usage response))) "output-tokens should be an int")
                           (is (int? (:total-tokens (:usage response))) "total-tokens should be an int")
                           (is (pos? (:input-tokens (:usage response))) "input-tokens should be positive")
                           (is (pos? (:output-tokens (:usage response))) "output-tokens should be positive")
                           (is (pos? (:total-tokens (:usage response))) "total-tokens should be positive"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-query-llm-provider-gemini-integration
  (async done
    (testing "INTEGRATION: validate Gemini API returns normalized response"
      (let [api-key (.-GEMINI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Gemini integration test - GEMINI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "gemini-2.5-flash" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== GEMINI API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches normalized LLMResponse schema"
                           ;; Validate normalized structure
                           (is (string? (:text response)) "text should be a string")
                           (is (seq (:text response)) "text should be non-empty")

                           ;; Validate usage map
                           (is (map? (:usage response)) "usage should be a map")
                           (is (int? (:input-tokens (:usage response))) "input-tokens should be an int")
                           (is (int? (:output-tokens (:usage response))) "output-tokens should be an int")
                           (is (int? (:total-tokens (:usage response))) "total-tokens should be an int")
                           (is (pos? (:input-tokens (:usage response))) "input-tokens should be positive")
                           (is (pos? (:output-tokens (:usage response))) "output-tokens should be positive")
                           (is (pos? (:total-tokens (:usage response))) "total-tokens should be positive"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))
