(ns gremllm.main.effects.llm-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.main.effects.llm-test :refer [mock-claude-response
                                                   mock-openai-response
                                                   mock-gemini-response]]))

(defn assert-matches-structure
  "Validates actual response matches mock structure, ignoring dynamic field values.
  - Static fields must match exactly
  - Dynamic fields are checked for existence only
  - Usage keys must match (but values can differ)"
  [actual mock static-fields dynamic-fields]
  ;; All mock keys should be present in actual
  (is (every? (set (keys actual)) (keys mock))
      (str "Missing keys: " (remove (set (keys actual)) (keys mock))))

  ;; Static fields must match exactly
  (doseq [field static-fields]
    (is (= (field actual) (field mock))
        (str field " should match: expected " (field mock) ", got " (field actual))))

  ;; Dynamic fields should exist (type checks left to caller if needed)
  (doseq [field dynamic-fields]
    (is (contains? actual field)
        (str field " should exist in response")))

  ;; Usage should have same keys as mock (handle both :usage and :usageMetadata)
  (let [usage-key (cond
                    (contains? actual :usage) :usage
                    (contains? actual :usageMetadata) :usageMetadata
                    :else nil)]
    (when usage-key
      (is (= (set (keys (usage-key actual))) (set (keys (usage-key mock))))
          (str usage-key " keys should match mock")))))

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
                                                   [:type :role :model :stop_reason :stop_sequence]
                                                   [:id :content])

                         ;; Provider-specific structural checks
                         (is (vector? (:content response)) "content should be a vector")
                         (is (seq (:content response)) "content should be non-empty")

                         ;; Content item structure validation
                         (testing "content item structure"
                           (let [content-item (first (:content response))]
                             (is (map? content-item)
                                 "content item should be a map")
                             (is (contains? content-item :type)
                                 "content item should have :type field")
                             (is (= "text" (:type content-item))
                                 "content item type should be 'text'")
                             (is (contains? content-item :text)
                                 "content item should have :text field")
                             (is (string? (:text content-item))
                                 "content text should be a string")))

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
                         (is (seq (:choices response)) "choices should be non-empty")

                         ;; API contract and nested structure validations
                         (testing "API contract fields"
                           (let [choice (first (:choices response))]
                             (is (number? (:index choice))
                                 "choice index should be a number")
                             (is (= 0 (:index choice))
                                 "first choice should have index 0")
                             (is (= "assistant" (get-in choice [:message :role]))
                                 "message role should be 'assistant'")
                             (is (contains? #{"stop" "length" "content_filter" "tool_calls" "function_call"}
                                            (:finish_reason choice))
                                 "finish_reason should be valid enum value")))

                         (testing "nested structure required by normalization"
                           (let [message (get-in response [:choices 0 :message])]
                             (is (map? message)
                                 "message should be a map")
                             (is (contains? message :role)
                                 "message should have :role field")
                             (is (contains? message :content)
                                 "message should have :content field")
                             (is (contains? message :refusal)
                                 "message should have :refusal field")
                             (is (contains? message :annotations)
                                 "message should have :annotations field")
                             (is (string? (:content message))
                                 "content should be a string")
                             (is (or (nil? (:refusal message)) (string? (:refusal message)))
                                 "refusal should be nil or string")
                             (is (vector? (:annotations message))
                                 "annotations should be a vector")))

                         (testing "usage structure"
                           (let [usage (:usage response)]
                             (is (map? usage) "usage should be a map")
                             (is (every? number? [(:prompt_tokens usage)
                                                  (:completion_tokens usage)
                                                  (:total_tokens usage)])
                                 "token counts should be numbers")))

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

                         ;; API contract and nested structure validations
                         (testing "API contract fields"
                           (let [candidate (first (:candidates response))]
                             (is (= "model" (get-in candidate [:content :role]))
                                 "assistant role should be 'model' in Gemini API")
                             (is (= 0 (:index candidate))
                                 "first candidate should have index 0")
                             (is (contains? #{"STOP" "MAX_TOKENS" "SAFETY" "RECITATION"}
                                            (:finishReason candidate))
                                 "finishReason should be valid enum value")))

                         (testing "nested structure required by normalization"
                           (let [candidate (first (:candidates response))]
                             (is (map? (:content candidate))
                                 "candidate content should be a map")
                             (is (vector? (get-in candidate [:content :parts]))
                                 "content parts should be a vector")
                             (is (string? (get-in candidate [:content :parts 0 :text]))
                                 "text should exist and be a string")))

                         (testing "usage metadata structure"
                           (let [usage (:usageMetadata response)]
                             (is (map? usage) "usageMetadata should be a map")
                             (is (every? number? [(:promptTokenCount usage)
                                                  (:candidatesTokenCount usage)
                                                  (:totalTokenCount usage)])
                                 "token counts should be numbers")))

                         (done)))
                (.catch (fn [error]
                          (is false (str "API call failed: " (.-message error)))
                          (done))))))))))
