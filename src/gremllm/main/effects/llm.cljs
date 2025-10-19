(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations"
  (:require [gremllm.main.llm :as llm]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn messages->gemini-format
  "Transform OpenAI/Anthropic message format to Gemini contents format.
  Maps {:role 'assistant' :content 'text'} to {:role 'model' :parts [{:text 'text'}]}.
  Pure function for easy testing."
  [messages]
  (mapv (fn [{:keys [role content]}]
          {:role (if (= role "assistant") "model" role)
           :parts [{:text content}]})
        messages))

(defn- log-and-throw-error [response model message-count body]
  (let [status (.-status response)
        status-text (.-statusText response)
        error-msg (str "API request failed (" status " " status-text ")")]
    (js/console.error "LLM API Error Details:"
                      (clj->js {:status status
                                :statusText status-text
                                :model model
                                :messageCount message-count
                                :responseBody body}))
    (throw (js/Error. (str error-msg ": " body)))))

(defn- handle-error-response [response model message-count]
  (.then (.text response)
         #(log-and-throw-error response model message-count %)))

(defn- handle-response [response model message-count]
  (if (.-ok response)
    (.json response)
    (handle-error-response response model message-count)))

(defn- normalize-anthropic-response
  "Transforms Anthropic API response to LLMResponse schema.
  Validates the result, throwing if Anthropic returns unexpected shape."
  [response]
  (js/console.log "Normalizing Anthropic response:"
                  (clj->js {:hasContent (contains? response :content)
                            :hasUsage (contains? response :usage)
                            :contentLength (count (:content response))
                            :inputTokens (get-in response [:usage :input_tokens])
                            :outputTokens (get-in response [:usage :output_tokens])}))
  (let [normalized {:text (get-in response [:content 0 :text])
                    :usage {:input-tokens (get-in response [:usage :input_tokens])
                            :output-tokens (get-in response [:usage :output_tokens])
                            :total-tokens (+ (get-in response [:usage :input_tokens])
                                             (get-in response [:usage :output_tokens]))}}
        result (m/coerce schema/LLMResponse normalized mt/default-value-transformer)]
    (when-not (m/validate schema/LLMResponse result)
      (let [explanation (m/explain schema/LLMResponse result)]
        (js/console.error "LLMResponse validation failed:"
                          (clj->js {:normalized normalized
                                    :result result
                                    :errors explanation}))
        (throw (js/Error. (str "Invalid LLMResponse shape: " (pr-str explanation))))))
    (js/console.log "Normalized response valid:"
                    (clj->js {:text (:text result)
                              :inputTokens (get-in result [:usage :input-tokens])
                              :outputTokens (get-in result [:usage :output-tokens])}))
    result))

(defmulti ^LLMResponse query-llm-provider
  "Dispatches to provider-specific implementation based on model string.

  Uses direct fetch instead of provider SDKs for simplicity—our use case is
  straightforward message exchange. Resist adding features (error handling,
  retries, streaming, etc.). Upgrade to SDKs when requirements outgrow this."
  (fn [_messages model _api-key] (llm/model->provider model)))

(defmethod query-llm-provider :anthropic
  [messages model api-key]
  (let [request-body {:model model
                      :max_tokens 8192
                      :messages messages}
        headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}]
    (js/console.log "[LLM Provider] Starting Anthropic request:"
                    (clj->js {:model model
                              :messageCount (count messages)
                              :hasApiKey (some? api-key)
                              :apiKeyPrefix (when api-key (subs api-key 0 (min 8 (count api-key))))}))
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then (fn [response]
                 (js/console.log "[LLM Provider] Received raw response:"
                                 (clj->js {:status (.-status response)
                                           :ok (.-ok response)
                                           :statusText (.-statusText response)}))
                 response))
        (.then #(handle-response % model (count messages)))
        (.then (fn [json]
                 (js/console.log "[LLM Provider] JSON parsed successfully")
                 json))
        (.then (fn [json]
                 (let [clj-data (js->clj json :keywordize-keys true)]
                   (js/console.log "[LLM Provider] Converted to Clojure:"
                                   (clj->js {:keys (keys clj-data)
                                             :hasContent (contains? clj-data :content)
                                             :hasUsage (contains? clj-data :usage)}))
                   clj-data)))
        (.then normalize-anthropic-response)
        (.catch (fn [error]
                  (js/console.error "[LLM Provider] Promise chain error:"
                                    (clj->js {:message (.-message error)
                                              :stack (.-stack error)}))
                  (throw error))))))

(defmethod query-llm-provider :openai
  [messages model api-key]
  (let [request-body {:model model
                      :max_tokens 8192
                      :messages messages}
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}]
    (-> (js/fetch "https://api.openai.com/v1/chat/completions"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))

(defmethod query-llm-provider :google
  [messages model api-key]
  (let [request-body {:contents (messages->gemini-format messages)
                      :generationConfig {:maxOutputTokens 8192}}
        headers {"Content-Type" "application/json"}
        url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                 model ":generateContent?key=" api-key)]
    (-> (js/fetch url
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))
