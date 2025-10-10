(ns gremllm.main.effects.llm-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.test-utils :refer [with-console-error-silenced]]))

;; Example send-message response:
;;
;; cljs.user=> (-> (llm/send-message test-messages api-key)
;;     (.then (fn [response]
;;              (println "Response:" response)
;;              (println "Content:" (-> response :content first :text))))
;;     (.catch (fn [error]
;;               (println "Error:" error)))))
;; #object[Promise [object Promise]]
;; cljs.user=> Response: {:id msg_01LaTD7HNzYujxh6ASPpTQ6T, :type message, :role assistant, :model claude-3-5-haiku-20241022, :content [{:type text, :text 4}], :stop_reason end_turn, :stop_sequence nil, :usage {:input_tokens 16, :cache_creation_input_tokens 0, :cache_read_input_tokens 0, :output_tokens 5, :service_tier standard}}
;; Content: 4

(defn mock-successful-fetch [response-data]
  (fn [url opts]
    (js/Promise.resolve
     #js {:ok true
          :json (fn []
                  (js/Promise.resolve
                   (clj->js response-data)))})))

(defn mock-failed-fetch [error-message]
  (fn [url opts]
    (js/Promise.reject (js/Error. error-message))))

(defn mock-http-error-fetch [status status-text body]
  (fn [_url _opts]
    (js/Promise.resolve
     #js {:ok false
          :status status
          :statusText status-text
          :text #(js/Promise.resolve body)})))

;; Canonical mock responses - validated against real API calls
;; Run integration tests to verify these match current API responses:
;;   ANTHROPIC_API_KEY=... OPENAI_API_KEY=... npm run test

(def mock-claude-response
  "Anthropic API response structure. Validated via test-query-llm-provider-anthropic-integration."
  {:id "msg_01LaTD7HNzYujxh6ASPpTQ6T"
   :type "message"
   :role "assistant"
   :model "claude-3-5-haiku-20241022"
   :content [{:type "text" :text "4"}]
   :stop_reason "end_turn"
   :stop_sequence nil
   :usage {:input_tokens 16
           :cache_creation_input_tokens 0
           :cache_read_input_tokens 0
           :output_tokens 5
           :service_tier "standard"}})

(def mock-openai-response
  "OpenAI API response structure. Validated via test-query-llm-provider-openai-integration."
  {:id "chatcmpl-123"
   :object "chat.completion"
   :created 1677652288
   :model "gpt-4o-mini"
   :choices [{:index 0
              :message {:role "assistant"
                        :content "4"}
              :finish_reason "stop"}]
   :usage {:prompt_tokens 9
           :completion_tokens 1
           :total_tokens 10
           :prompt_tokens_details {:cached_tokens 0
                                   :audio_tokens 0}
           :completion_tokens_details {:reasoning_tokens 0
                                       :audio_tokens 0
                                       :accepted_prediction_tokens 0
                                       :rejected_prediction_tokens 0}}})

;; Test Helpers

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

  ;; Usage should have same keys as mock
  (is (= (set (keys (:usage actual))) (set (keys (:usage mock))))
      (str "usage keys should match mock")))

;; Integration Tests
;; These call real APIs to validate our mocks match actual responses.
;; Run with: ANTHROPIC_API_KEY=... OPENAI_API_KEY=... npm run test

(deftest ^:integration test-query-llm-provider-anthropic-integration
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

(deftest ^:integration test-query-llm-provider-openai-integration
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

;; Unit Tests

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

(deftest test-query-llm-provider-anthropic
  (testing "successfully parses Claude API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "claude-3-5-haiku-latest"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-claude-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [response]
                   (testing "response structure"
                     (is (= (:id response) "msg_01LaTD7HNzYujxh6ASPpTQ6T"))
                     (is (= (:role response) "assistant")))

                   (testing "content extraction"
                     (is (= (-> response :content first :text) "4")))))
          (.finally #(set! js/fetch original-fetch))))))

(deftest test-query-llm-provider-error
  (testing "API errors are propagated as rejected promises"
    (let [original-fetch js/fetch
          test-messages [{:role "user" :content "Hello"}]
          test-model "claude-3-5-haiku-latest"
          test-api-key "test-key"
          error-message "Network error"]

      (set! js/fetch (mock-failed-fetch error-message))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [_]
                   (is false "Should not succeed")))
          (.catch (fn [error]
                    (is (= (.-message error) error-message))))
          (.finally #(set! js/fetch original-fetch))))))

(deftest test-query-llm-provider-http-error
  (testing "HTTP error responses include status and body"
    (let [original-fetch js/fetch
          restore-console (with-console-error-silenced)]
      (set! js/fetch (mock-http-error-fetch 401 "Unauthorized" "Invalid API key"))

      (-> (llm/query-llm-provider [{:role "user" :content "test"}] "claude-3-5-haiku-latest" "bad-key")
          (.then #(is false "Should not succeed"))
          (.catch (fn [error]
                    (is (re-find #"401.*Unauthorized.*Invalid API key" (.-message error)))))
          (.finally (fn []
                      (set! js/fetch original-fetch)
                      (restore-console)))))))

(deftest test-query-llm-provider-openai
  (testing "successfully parses OpenAI API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "gpt-4o-mini"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-openai-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [response]
                   (testing "response structure"
                     (is (= (:id response) "chatcmpl-123"))
                     (is (= (:object response) "chat.completion")))

                   (testing "content extraction"
                     (is (= (-> response :choices first :message :content) "4")))))
          (.finally #(set! js/fetch original-fetch))))))
