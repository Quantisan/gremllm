(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations"
  (:require [clojure.string :as str]))

(defn model->provider
  "Infers provider from model string. Pure function for easy testing."
  [model]
  (cond
    (str/starts-with? model "claude-") :anthropic
    (str/starts-with? model "gpt-")    :openai
    (str/starts-with? model "gemini-") :google
    :else (throw (js/Error. (str "Unknown provider for model: " model)))))

(defn provider->api-key-keyword
  "Maps provider to safeStorage lookup key. Pure function for easy testing."
  [provider]
  (case provider
    :anthropic :anthropic-api-key
    :openai    :openai-api-key
    :google    :gemini-api-key))

(defn provider->env-var-name
  "Maps provider to environment variable name. Pure function for easy testing."
  [provider]
  (case provider
    :anthropic "ANTHROPIC_API_KEY"
    :openai    "OPENAI_API_KEY"
    :google    "GEMINI_API_KEY"))

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

(defmulti query-llm-provider
  "Dispatches to provider-specific implementation based on model string.

  Uses direct fetch instead of provider SDKs for simplicity—our use case is
  straightforward message exchange. Resist adding features (error handling,
  retries, streaming, etc.). Upgrade to SDKs when requirements outgrow this."
  (fn [_messages model _api-key] (model->provider model)))

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
        (.then #(js->clj % :keywordize-keys true)))))