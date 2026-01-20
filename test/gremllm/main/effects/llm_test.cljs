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
   :model "claude-haiku-4-5-20251001"
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
   :model "gpt-5-nano"
   :choices [{:index 0
              :message {:role "assistant"
                        :content "4"
                        :refusal nil
                        :annotations []}
              :finish_reason "stop"}]
   :usage {:prompt_tokens 9
           :completion_tokens 1
           :total_tokens 10
           :prompt_tokens_details {:cached_tokens 0
                                   :audio_tokens 0}
           :completion_tokens_details {:reasoning_tokens 0
                                       :audio_tokens 0
                                       :accepted_prediction_tokens 0
                                       :rejected_prediction_tokens 0}}
   :service_tier "default"
   :system_fingerprint nil})

(def mock-gemini-response
  "Google Gemini API response structure. Validated via test-query-llm-provider-gemini-integration."
  {:candidates [{:content {:parts [{:text "4"}]
                           :role "model"}
                 :finishReason "STOP"
                 :index 0}]
   :usageMetadata {:promptTokenCount 4
                   :candidatesTokenCount 1
                   :totalTokenCount 24
                   :thoughtsTokenCount 0
                   :promptTokensDetails [{:modality "TEXT"
                                          :tokenCount 4}]}
   :modelVersion "gemini-3-flash-preview"
   :responseId "tpboaMabLejN1e8PzMOD0Ak"})

;; Unit Tests

(deftest test-messages->gemini-format
  (testing "transforms user messages"
    (is (= [{:role "user" :parts [{:text "Hello"}]}]
           (llm/messages->gemini-format [{:role "user" :content "Hello"}]))))

  (testing "transforms assistant messages to model role"
    (is (= [{:role "model" :parts [{:text "Hi there"}]}]
           (llm/messages->gemini-format [{:role "assistant" :content "Hi there"}]))))

  (testing "transforms multi-turn conversation"
    (is (= [{:role "user" :parts [{:text "2+2"}]}
            {:role "model" :parts [{:text "4"}]}
            {:role "user" :parts [{:text "Thanks"}]}]
           (llm/messages->gemini-format
            [{:role "user" :content "2+2"}
             {:role "assistant" :content "4"}
             {:role "user" :content "Thanks"}]))))

  (testing "message with single attachment"
    (is (= [{:role "user"
             :parts [{:inline_data {:mime_type "image/png"
                                    :data "iVBORw0KG..."}}
                     {:text "What's in this image?"}]}]
           (llm/messages->gemini-format
            [{:role "user"
              :content "What's in this image?"
              :attachments [{:mime-type "image/png"
                             :data "iVBORw0KG..."}]}]))))

  (testing "message with multiple attachments"
    (is (= [{:role "user"
             :parts [{:inline_data {:mime_type "image/png"
                                    :data "base64-data-1"}}
                     {:inline_data {:mime_type "application/pdf"
                                    :data "base64-data-2"}}
                     {:text "Describe these files"}]}]
           (llm/messages->gemini-format
            [{:role "user"
              :content "Describe these files"
              :attachments [{:mime-type "image/png"
                             :data "base64-data-1"}
                            {:mime-type "application/pdf"
                             :data "base64-data-2"}]}]))))

  (testing "backward compatibility - no attachments field"
    (is (= [{:role "user" :parts [{:text "Hello"}]}]
           (llm/messages->gemini-format [{:role "user" :content "Hello"}]))))

  (testing "empty attachments array"
    (is (= [{:role "user" :parts [{:text "Hello"}]}]
           (llm/messages->gemini-format [{:role "user" :content "Hello" :attachments []}]))))

  (testing "attachment-only message (nil content)"
    (is (= [{:role "user"
             :parts [{:inline_data {:mime_type "image/png"
                                    :data "base64-data"}}]}]
           (llm/messages->gemini-format
            [{:role "user"
              :content nil
              :attachments [{:mime-type "image/png"
                             :data "base64-data"}]}])))))

(deftest test-messages->openai-format
  (testing "messages without attachments use simple string content"
    (is (= [{:role "user" :content "Hello"}]
           (llm/messages->openai-format [{:role "user" :content "Hello"}]))))

  (testing "messages with attachments use multimodal content with data URL"
    (is (= [{:role "user"
             :content [{:type "file"
                        :file {:filename "doc.pdf"
                               :file_data "data:application/pdf;base64,abc123"}}
                       {:type "text" :text "Summarize this"}]}]
           (llm/messages->openai-format
            [{:role "user"
              :content "Summarize this"
              :attachments [{:mime-type "application/pdf"
                             :data "abc123"
                             :filename "doc.pdf"}]}]))))
  (testing "markdown attachments are converted to text parts"
    (is (= [{:role "user"
             :content [{:type "text"
                        :text "Attachment (notes.md):\n\nHello world"}
                       {:type "text" :text "Summarize this"}]}]
           (llm/messages->openai-format
            [{:role "user"
              :content "Summarize this"
              :attachments [{:mime-type "text/markdown"
                             :data "SGVsbG8gd29ybGQ="
                             :filename "notes.md"}]}])))))

(deftest test-messages->anthropic-format
  (testing "messages without attachments use simple string content"
    (is (= [{:role "user" :content "Hello"}]
           (llm/messages->anthropic-format [{:role "user" :content "Hello"}]))))

  (testing "image attachments become image blocks"
    (is (= [{:role "user"
             :content [{:type "image"
                        :source {:type "base64"
                                 :media_type "image/png"
                                 :data "iVBORw0KG..."}}
                       {:type "text" :text "What's in this image?"}]}]
           (llm/messages->anthropic-format
            [{:role "user"
              :content "What's in this image?"
              :attachments [{:mime-type "image/png"
                             :data "iVBORw0KG..."}]}]))))

  (testing "PDF attachments become document blocks"
    (is (= [{:role "user"
             :content [{:type "document"
                        :source {:type "base64"
                                 :media_type "application/pdf"
                                 :data "JVBERi0..."}}
                       {:type "text" :text "Summarize this"}]}]
           (llm/messages->anthropic-format
            [{:role "user"
              :content "Summarize this"
              :attachments [{:mime-type "application/pdf"
                             :data "JVBERi0..."}]}]))))

  (testing "text/markdown attachments become text blocks"
    (is (= [{:role "user"
             :content [{:type "text"
                        :text "Attachment (notes.md):\n\nHello world"}
                       {:type "text" :text "Summarize this"}]}]
           (llm/messages->anthropic-format
            [{:role "user"
              :content "Summarize this"
              :attachments [{:mime-type "text/markdown"
                             :data "SGVsbG8gd29ybGQ="
                             :filename "notes.md"}]}]))))

  (testing "multiple attachments in one message"
    (is (= [{:role "user"
             :content [{:type "image"
                        :source {:type "base64"
                                 :media_type "image/png"
                                 :data "img-data"}}
                       {:type "document"
                        :source {:type "base64"
                                 :media_type "application/pdf"
                                 :data "pdf-data"}}
                       {:type "text" :text "Compare these"}]}]
           (llm/messages->anthropic-format
            [{:role "user"
              :content "Compare these"
              :attachments [{:mime-type "image/png"
                             :data "img-data"}
                            {:mime-type "application/pdf"
                             :data "pdf-data"}]}]))))

  (testing "empty attachments array"
    (is (= [{:role "user" :content "Hello"}]
           (llm/messages->anthropic-format [{:role "user" :content "Hello" :attachments []}]))))

  (testing "attachment-only message (nil content)"
    (is (= [{:role "user"
             :content [{:type "image"
                        :source {:type "base64"
                                 :media_type "image/png"
                                 :data "base64-data"}}]}]
           (llm/messages->anthropic-format
            [{:role "user"
              :content nil
              :attachments [{:mime-type "image/png"
                             :data "base64-data"}]}])))))

(deftest test-normalize-anthropic-response
  (testing "transforms Anthropic response to normalized LLMResponse schema"
    (is (= {:text "4"
            :usage {:input-tokens 16
                    :output-tokens 5
                    :total-tokens 21}}
           (llm/normalize-anthropic-response mock-claude-response)))))

(deftest test-normalize-anthropic-response-with-reasoning
  (testing "extracts reasoning block from reasoning response"
    (let [response-with-reasoning {:id "msg_123"
                                   :type "message"
                                   :role "assistant"
                                   :content [{:type "thinking"
                                              :thinking "Let me work through this step by step..."}
                                             {:type "text"
                                              :text "The answer is 4."}]
                                   :usage {:input_tokens 100
                                           :output_tokens 50}}]
      (is (= {:text "The answer is 4."
              :reasoning "Let me work through this step by step..."
              :usage {:input-tokens 100
                      :output-tokens 50
                      :total-tokens 150}}
             (llm/normalize-anthropic-response response-with-reasoning))))))

(deftest test-normalize-openai-response
  (testing "transforms OpenAI response to normalized LLMResponse schema"
    (is (= {:text "4"
            :usage {:input-tokens 9
                    :output-tokens 1
                    :total-tokens 10
                    :reasoning-tokens 0}}
           (llm/normalize-openai-response mock-openai-response)))))

(deftest test-normalize-gemini-response
  (testing "transforms Gemini response to normalized LLMResponse schema"
    (is (= {:text "4"
            :usage {:input-tokens 4
                    :output-tokens 1
                    :total-tokens 24}}
           (llm/normalize-gemini-response mock-gemini-response)))))

(deftest test-normalize-gemini-response-with-reasoning
  (testing "extracts reasoning from parts where thought: true"
    (let [response-with-reasoning {:candidates [{:content {:parts [{:text "Let me work through this..."
                                                                    :thought true}
                                                                   {:text "The answer is 4."}]
                                                           :role "model"}
                                                 :finishReason "STOP"}]
                                   :usageMetadata {:promptTokenCount 10
                                                   :candidatesTokenCount 50
                                                   :totalTokenCount 60
                                                   :thoughtsTokenCount 30}}]
      (is (= {:text "The answer is 4."
              :reasoning "Let me work through this..."
              :usage {:input-tokens 10
                      :output-tokens 50
                      :total-tokens 60
                      :reasoning-tokens 30}}
             (llm/normalize-gemini-response response-with-reasoning))))))

(deftest test-query-llm-provider-anthropic
  (testing "successfully parses and normalizes Claude API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "claude-3-5-haiku-latest"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-claude-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key false)
          (.then (fn [response]
                   (testing "normalized response structure"
                     (is (= {:text "4"
                             :usage {:input-tokens 16
                                     :output-tokens 5
                                     :total-tokens 21}}
                            response)))))
          (.finally #(set! js/fetch original-fetch))))))

(deftest test-query-llm-provider-error
  (testing "API errors are propagated as rejected promises"
    (let [original-fetch js/fetch
          test-messages [{:role "user" :content "Hello"}]
          test-model "claude-3-5-haiku-latest"
          test-api-key "test-key"
          error-message "Network error"]

      (set! js/fetch (mock-failed-fetch error-message))

      (-> (llm/query-llm-provider test-messages test-model test-api-key false)
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

      (-> (llm/query-llm-provider [{:role "user" :content "test"}] "claude-3-5-haiku-latest" "bad-key" false)
          (.then #(is false "Should not succeed"))
          (.catch (fn [error]
                    (is (re-find #"401.*Unauthorized.*Invalid API key" (.-message error)))))
          (.finally (fn []
                      (set! js/fetch original-fetch)
                      (restore-console)))))))

(deftest test-query-llm-provider-openai
  (testing "successfully parses and normalizes OpenAI API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "gpt-4o-mini"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-openai-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key false)
          (.then (fn [response]
                   (testing "normalized response structure"
                     (is (= {:text "4"
                             :usage {:input-tokens 9
                                     :output-tokens 1
                                     :total-tokens 10
                                     :reasoning-tokens 0}}
                            response)))))
          (.finally #(set! js/fetch original-fetch))))))

(deftest test-query-llm-provider-gemini
  (testing "successfully parses and normalizes Gemini API response"
    (let [original-fetch js/fetch
          test-messages  [{:role "user" :content "2+2"}]
          test-model     "gemini-3-flash-preview"
          test-api-key   "test-key"]

      (set! js/fetch (mock-successful-fetch mock-gemini-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key false)
          (.then (fn [response]
                   (testing "normalized response structure"
                     (is (= {:text "4"
                             :usage {:input-tokens 4
                                     :output-tokens 1
                                     :total-tokens 24}}
                            response)))))
          (.finally #(set! js/fetch original-fetch))))))
