(ns gremllm.main.effects.llm-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.main.effects.llm-test :refer [mock-claude-response
                                                   mock-openai-response
                                                   mock-gemini-response]]))

(def test-api-messages
  "Simple API-formatted messages for integration testing.
  Shared across integration tests and schema boundary tests."
  [{:role "user" :content "2+2"}])

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

;; Integration Tests: Validate mock fixtures match real API responses
;;
;; Calls real APIs and compares structure against mock fixtures to ensure:
;; - Mock fixtures accurately represent actual provider responses
;; - Normalization functions can extract data from real responses
;; - Unit tests won't pass while production breaks on API changes
;;
;; This is why fetch-raw-provider-response exists—enables testing the external API boundary.
;;
;; Run with: ANTHROPIC_API_KEY=... OPENAI_API_KEY=... GEMINI_API_KEY=... npm run test:integration

(defn with-provider-api
  "Runs validate-fn against raw provider response if API key is available.
   Handles env check, API call, logging, and error catching."
  [{:keys [name env-key model]} validate-fn done]
  (let [api-key (aget (.-env js/process) env-key)]
    (if-not api-key
      (do (js/console.warn (str "Skipping " name " integration test - " env-key " not set"))
          (done))
      (-> (llm/fetch-raw-provider-response test-api-messages model api-key)
          (.then (fn [response]
                   (js/console.log (str "\n=== " name " RAW API RESPONSE ==="))
                   (js/console.log (js/JSON.stringify (clj->js response) nil 2))
                   (validate-fn response)
                   (done)))
          (.catch (fn [error]
                    (is false (str "API call failed: " (.-message error)))
                    (done)))))))

(deftest test-fetch-provider-response-anthropic-integration
  (async done
    (testing "INTEGRATION: validate Anthropic API structure matches mock fixture"
      (with-provider-api
        {:name "Anthropic" :env-key "ANTHROPIC_API_KEY" :model "claude-haiku-4-5-20251001"}
        (fn [response]
          (assert-matches-structure response mock-claude-response
                                    [:type :role :model :stop_reason :stop_sequence]
                                    [:id :content])
          (is (vector? (:content response)) "content should be a vector")
          (is (seq (:content response)) "content should be non-empty")
          (testing "content item structure"
            (let [content-item (first (:content response))]
              (is (map? content-item) "content item should be a map")
              (is (contains? content-item :type) "content item should have :type field")
              (is (= "text" (:type content-item)) "content item type should be 'text'")
              (is (contains? content-item :text) "content item should have :text field")
              (is (string? (:text content-item)) "content text should be a string"))))
        done))))

(deftest test-fetch-provider-response-openai-integration
  (async done
    (testing "INTEGRATION: validate OpenAI API structure matches mock fixture"
      (with-provider-api
        {:name "OpenAI" :env-key "OPENAI_API_KEY" :model "gpt-5-nano"}
        (fn [response]
          (assert-matches-structure response mock-openai-response
                                    [:object]
                                    [:id :created :model :choices])
          (is (vector? (:choices response)) "choices should be a vector")
          (is (seq (:choices response)) "choices should be non-empty")
          (testing "API contract fields"
            (let [choice (first (:choices response))]
              (is (number? (:index choice)) "choice index should be a number")
              (is (= 0 (:index choice)) "first choice should have index 0")
              (is (= "assistant" (get-in choice [:message :role])) "message role should be 'assistant'")
              (is (contains? #{"stop" "length" "content_filter" "tool_calls" "function_call"}
                             (:finish_reason choice))
                  "finish_reason should be valid enum value")))
          (testing "nested structure required by normalization"
            (let [message (get-in response [:choices 0 :message])]
              (is (map? message) "message should be a map")
              (is (contains? message :role) "message should have :role field")
              (is (contains? message :content) "message should have :content field")
              (is (contains? message :refusal) "message should have :refusal field")
              (is (contains? message :annotations) "message should have :annotations field")
              (is (string? (:content message)) "content should be a string")
              (is (or (nil? (:refusal message)) (string? (:refusal message))) "refusal should be nil or string")
              (is (vector? (:annotations message)) "annotations should be a vector")))
          (testing "usage structure"
            (let [usage (:usage response)]
              (is (map? usage) "usage should be a map")
              (is (every? number? [(:prompt_tokens usage) (:completion_tokens usage) (:total_tokens usage)])
                  "token counts should be numbers"))))
        done))))

(deftest test-fetch-provider-response-gemini-integration
  (async done
    (testing "INTEGRATION: validate Gemini API structure matches mock fixture"
      (with-provider-api
        {:name "Gemini" :env-key "GEMINI_API_KEY" :model "gemini-2.5-flash-lite"}
        (fn [response]
          (assert-matches-structure response mock-gemini-response
                                    []
                                    [:candidates :modelVersion :responseId])
          (is (vector? (:candidates response)) "candidates should be a vector")
          (testing "API contract fields"
            (let [candidate (first (:candidates response))]
              (is (= "model" (get-in candidate [:content :role])) "assistant role should be 'model' in Gemini API")
              (is (= 0 (:index candidate)) "first candidate should have index 0")
              (is (contains? #{"STOP" "MAX_TOKENS" "SAFETY" "RECITATION"} (:finishReason candidate))
                  "finishReason should be valid enum value")))
          (testing "nested structure required by normalization"
            (let [candidate (first (:candidates response))]
              (is (map? (:content candidate)) "candidate content should be a map")
              (is (vector? (get-in candidate [:content :parts])) "content parts should be a vector")
              (is (string? (get-in candidate [:content :parts 0 :text])) "text should exist and be a string")))
          (testing "usage metadata structure"
            (let [usage (:usageMetadata response)]
              (is (map? usage) "usageMetadata should be a map")
              (is (every? number? [(:promptTokenCount usage) (:candidatesTokenCount usage) (:totalTokenCount usage)])
                  "token counts should be numbers"))))
        done))))

;; Shared test data for markdown attachment tests
(def test-markdown-base64
  "Base64-encoded markdown: '# Favorite Programming Languages\n\nMy top three languages are:\n- Clojure\n- Python\n- Rust'"
  "IyBGYXZvcml0ZSBQcm9ncmFtbWluZyBMYW5ndWFnZXMKCk15IHRvcCB0aHJlZSBsYW5ndWFnZXMgYXJlOgotIENsb2p1cmUKLSBQeXRob24KLSBSdXN0Cg==")

(def markdown-attachment-providers
  "Provider configurations for markdown attachment integration tests."
  {:gemini    {:name "Gemini"
               :env-key "GEMINI_API_KEY"
               :model "gemini-2.5-flash-lite"
               :include-filename? false}
   :openai    {:name "OpenAI"
               :env-key "OPENAI_API_KEY"
               :model "gpt-5-nano"
               :include-filename? true}
   :anthropic {:name "Anthropic"
               :env-key "ANTHROPIC_API_KEY"
               :model "claude-haiku-4-5-20251001"
               :include-filename? true}})

(defn run-markdown-attachment-test
  "Runs a markdown attachment integration test for a given provider.
   Returns a promise that resolves when the test completes."
  [{:keys [name env-key model include-filename?]} done]
  (let [api-key (aget (.-env js/process) env-key)]
    (if-not api-key
      (do (js/console.warn (str "Skipping " name " markdown attachment test - " env-key " not set"))
          (done))
      (let [attachment (cond-> {:mime-type "text/markdown"
                                :data test-markdown-base64}
                         include-filename? (assoc :filename "languages.md"))
            test-messages [{:role "user"
                            :content "What are the three programming languages listed in this document?"
                            :attachments [attachment]}]]
        (-> (llm/query-llm-provider test-messages model api-key false)
            (.then (fn [response]
                     (js/console.log (str "\n=== " name " MARKDOWN ATTACHMENT RESPONSE ==="))
                     (js/console.log (js/JSON.stringify (clj->js response) nil 2))
                     (is (string? (:text response)) "Should receive text response")
                     (is (and (re-find #"(?i)clojure" (:text response))
                              (re-find #"(?i)python" (:text response))
                              (re-find #"(?i)rust" (:text response)))
                         "Response should identify all three languages from markdown")
                     (is (pos-int? (get-in response [:usage :total-tokens]))
                         "Should include valid usage metadata")
                     (done)))
            (.catch (fn [error]
                      (is false (str "API call with markdown attachment failed: " (.-message error)))
                      (done))))))))

(deftest test-query-llm-provider-gemini-with-markdown-attachment-integration
  (async done
    (testing "INTEGRATION: validate Gemini accepts text/markdown with real API"
      (run-markdown-attachment-test (:gemini markdown-attachment-providers) done))))

(deftest test-query-llm-provider-openai-with-markdown-attachment-integration
  (async done
    (testing "INTEGRATION: validate OpenAI handles markdown attachment via text conversion"
      (run-markdown-attachment-test (:openai markdown-attachment-providers) done))))

(deftest test-query-llm-provider-anthropic-with-markdown-attachment-integration
  (async done
    (testing "INTEGRATION: validate Anthropic handles markdown attachment via text conversion"
      (run-markdown-attachment-test (:anthropic markdown-attachment-providers) done))))
