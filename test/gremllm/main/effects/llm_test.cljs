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
  (fn [_url _opts]
    (js/Promise.resolve
     #js {:ok true
          :json (fn []
                  (js/Promise.resolve
                   (clj->js response-data)))})))

(defn mock-failed-fetch [error-message]
  (fn [_url _opts]
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
           :cache_creation {:ephemeral_5m_input_tokens 0
                            :ephemeral_1h_input_tokens 0}
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

(def mock-gemini-response
  "Google Gemini API response structure. Validated via test-query-llm-provider-gemini-integration."
  {:candidates [{:content {:parts [{:text "4"}]
                           :role "model"}
                 :finishReason "STOP"
                 :index 0}]
   :usageMetadata {:promptTokenCount 9
                   :candidatesTokenCount 1
                   :totalTokenCount 10}
   :modelVersion "gemini-2.5-flash"})

;; Shared test utilities (used by llm-integration-test)

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
      "usage keys should match mock"))

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

(deftest test-messages->gemini-contents
  (testing "transforms user messages"
    (is (= [{:role "user" :parts [{:text "Hello"}]}]
           (llm/messages->gemini-contents [{:role "user" :content "Hello"}]))))

  (testing "transforms assistant messages to model role"
    (is (= [{:role "model" :parts [{:text "Hi there"}]}]
           (llm/messages->gemini-contents [{:role "assistant" :content "Hi there"}]))))

  (testing "transforms multi-turn conversation"
    (is (= [{:role "user" :parts [{:text "2+2"}]}
            {:role "model" :parts [{:text "4"}]}
            {:role "user" :parts [{:text "Thanks"}]}]
           (llm/messages->gemini-contents
            [{:role "user" :content "2+2"}
             {:role "assistant" :content "4"}
             {:role "user" :content "Thanks"}])))))

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

(deftest test-query-llm-provider-gemini
  (testing "successfully parses Gemini API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "gemini-2.0-flash"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-gemini-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [response]
                   (testing "response structure"
                     (is (= (:modelVersion response) "gemini-2.0-flash"))
                     (is (= (-> response :candidates first :finishReason) "STOP")))

                   (testing "content extraction"
                     (is (= (-> response :candidates first :content :parts first :text) "4")))))
          (.finally #(set! js/fetch original-fetch))))))
