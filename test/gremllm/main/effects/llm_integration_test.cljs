(ns gremllm.main.effects.llm-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.main.effects.llm-test :refer [mock-claude-response
                                                    mock-openai-response
                                                    mock-gemini-response
                                                    assert-matches-structure]]))

;; Integration Tests
;; These call real APIs to validate that mock fixtures accurately represent actual API responses.
;; This ensures unit test mocks stay synchronized with provider APIs.
;; Run with: ANTHROPIC_API_KEY=... OPENAI_API_KEY=... GEMINI_API_KEY=... npm run test:integration

(deftest test-fetch-provider-response-anthropic-integration
  (async done
    (testing "INTEGRATION: validate Anthropic API structure matches mock fixture"
      (let [api-key (.-ANTHROPIC_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Anthropic integration test - ANTHROPIC_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/fetch-provider-response test-messages "claude-haiku-4-5-20251001" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== ANTHROPIC RAW API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         ;; Validate raw API structure against mock fixture
                         (assert-matches-structure response
                                                   mock-claude-response
                                                   [:type :role]
                                                   [:id :model :stop_reason :stop_sequence :content])

                         ;; Provider-specific structural checks
                         (is (vector? (:content response)) "content should be a vector")

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-fetch-provider-response-openai-integration
  (async done
    (testing "INTEGRATION: validate OpenAI API structure matches mock fixture"
      (let [api-key (.-OPENAI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping OpenAI integration test - OPENAI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/fetch-provider-response test-messages "gpt-5-nano" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== OPENAI RAW API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         ;; Validate raw API structure against mock fixture
                         (assert-matches-structure response
                                                   mock-openai-response
                                                   [:object]
                                                   [:id :created :model :choices])

                         ;; Provider-specific structural checks
                         (is (vector? (:choices response)) "choices should be a vector")

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))

(deftest test-fetch-provider-response-gemini-integration
  (async done
    (testing "INTEGRATION: validate Gemini API structure matches mock fixture"
      (let [api-key (.-GEMINI_API_KEY (.-env js/process))]
        (if-not api-key
          (do (js/console.warn "Skipping Gemini integration test - GEMINI_API_KEY not set")
              (done))
          (let [test-messages [{:role "user" :content "2+2"}]]
            (-> (llm/fetch-provider-response test-messages "gemini-2.5-flash-lite" api-key)
                (.then (fn [response]
                         (js/console.log "\n=== GEMINI RAW API RESPONSE ===")
                         (js/console.log (js/JSON.stringify (clj->js response) nil 2))

                         ;; Validate raw API structure against mock fixture
                         (assert-matches-structure response
                                                   mock-gemini-response
                                                   []
                                                   [:candidates :modelVersion :responseId])

                         ;; Provider-specific structural checks
                         (is (vector? (:candidates response)) "candidates should be a vector")

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))
