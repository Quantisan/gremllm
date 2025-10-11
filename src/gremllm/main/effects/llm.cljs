(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations"
  (:require [gremllm.main.llm :as llm]))

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
  "Transforms Anthropic API response to LLMResponse schema."
  [response]
  {:text (get-in response [:content 0 :text])
   :usage {:input-tokens (get-in response [:usage :input_tokens])
           :output-tokens (get-in response [:usage :output_tokens])
           :total-tokens (+ (get-in response [:usage :input_tokens])
                            (get-in response [:usage :output_tokens]))}})

(defmulti query-llm-provider
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
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true))
        (.then normalize-anthropic-response))))

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
