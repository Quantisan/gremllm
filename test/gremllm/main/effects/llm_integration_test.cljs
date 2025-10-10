(ns gremllm.main.effects.llm-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.main.effects.llm-test :refer [mock-claude-response
                                                   mock-openai-response
                                                   mock-gemini-response
                                                   assert-matches-structure]]))

;; Integration Tests
;; These call real APIs to validate our mocks match actual responses.
;; Run with: ANTHROPIC_API_KEY=... OPENAI_API_KEY=... GEMINI_API_KEY=... npm run test:integration

(deftest test-query-llm-provider-anthropic-integration
  (async done
    (testing "INTEGRATION: validate mock-claude-response against real API"
      (let [api-key (.-ANTHROPIC_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Anthropic integration test - ANTHROPIC_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "claude-3-5-haiku-latest" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== ANTHROPIC API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches mock-claude-response structure"
                           (assert-matches-structure response
                                                     mock-claude-response
                                                     [:type :role :stop_reason :stop_sequence]
                                                     [:id :model])
                           ;; Anthropic-specific structure checks
                           (is (vector? (:content response)) "content should be a vector")
                           (is (every? #(contains? % :type) (:content response))
                               "content items should have :type key"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-query-llm-provider-openai-integration
  (async done
    (testing "INTEGRATION: validate mock-openai-response against real API"
      (let [api-key (.-OPENAI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping OpenAI integration test - OPENAI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "gpt-4o-mini" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== OPENAI API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches mock-openai-response structure"
                           (assert-matches-structure response
                                                     mock-openai-response
                                                     [:object]
                                                     [:id :created :model])
                           ;; OpenAI-specific structure checks
                           (is (vector? (:choices response)) "choices should be a vector")
                           (is (every? #(contains? % :message) (:choices response))
                               "choices items should have :message key"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-query-llm-provider-gemini-integration
  (async done
    (testing "INTEGRATION: validate mock-gemini-response against real API"
      (let [api-key (.-GEMINI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Gemini integration test - GEMINI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/query-llm-provider test-messages "gemini-2.5-flash" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== GEMINI API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         (testing "response matches mock-gemini-response structure"
                           (assert-matches-structure response
                                                     mock-gemini-response
                                                     []
                                                     [:modelVersion :responseId])
                           ;; Gemini-specific structure checks
                           (is (vector? (:candidates response)) "candidates should be a vector")
                           (is (every? #(contains? % :content) (:candidates response))
                               "candidates items should have :content key"))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))
