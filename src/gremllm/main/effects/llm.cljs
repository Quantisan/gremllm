(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations"
  (:require [clojure.string :as str]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(defn messages->gemini-format
  "Transform OpenAI/Anthropic message format to Gemini contents format.
  Maps {:role 'assistant' :content 'text'} to {:role 'model' :parts [{:text 'text'}]}.
  Supports optional :attachments for multimodal messages with inline_data.
  Pure function for easy testing."
  [messages]
  (let [result (mapv (fn [{:keys [role content attachments]}]
                       {:role (if (= role "assistant") "model" role)
                        :parts (concat
                                 ;; Attachment parts (inline_data with base64)
                                 (mapv (fn [{:keys [mime-type data]}]
                                         {:inline_data {:mime_type mime-type
                                                        :data data}})
                                       (or attachments []))
                                 ;; Text part - only include if content is non-empty
                                 (when (not (str/blank? content))
                                   [{:text content}]))})
                     messages)]
    (js/console.log "[chat:transform:gemini]"
                    (clj->js {:messages (count messages)
                              :parts (mapv (fn [part]
                                             (cond
                                               (:text part) {:type "text"}
                                               (:inline_data part) {:type "inline_data"
                                                                    :mime_type (get-in part [:inline_data :mime_type])}
                                               :else {:type "unknown"}))
                                           (:parts (first result)))}))
    result))

(defn messages->openai-format
  "Transform messages to OpenAI multimodal format.
   Maps attachments to {:type 'file' :file {:filename ... :file_data 'data:mime;base64,...'}}.
   text/markdown + text/plain attachments become {:type 'text' :text '...'}.
   Text content becomes {:type 'text' :text '...'}.
   Pure function for easy testing."
  [messages]
  (let [text-attachment-types #{"text/markdown" "text/plain"}
        base64->utf8 (fn [data]
                       (.toString (js/Buffer.from data "base64") "utf8"))]
    (mapv (fn [{:keys [role content attachments]}]
            (let [attachment-parts (mapv (fn [{:keys [mime-type data filename]}]
                                           (if (contains? text-attachment-types mime-type)
                                             {:type "text"
                                              :text (str "Attachment"
                                                         (when filename (str " (" filename ")"))
                                                         ":\n\n"
                                                         (base64->utf8 data))}
                                             {:type "file"
                                              :file {:filename (or filename "attachment")
                                                     :file_data (str "data:" mime-type ";base64," data)}}))
                                         (or attachments []))
                  text-parts (when (not (str/blank? content))
                               [{:type "text" :text content}])
                  all-parts (into (vec attachment-parts) text-parts)]
              (if (seq attachments)
                {:role role :content all-parts}
                {:role role :content content})))
          messages)))

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

(defn normalize-anthropic-response
  "Transforms Anthropic API response to LLMResponse schema.
  Validates the result, throwing if Anthropic returns unexpected shape."
  [response]
  (m/coerce schema/LLMResponse
            {:text (get-in response [:content 0 :text])
             :usage {:input-tokens (get-in response [:usage :input_tokens])
                     :output-tokens (get-in response [:usage :output_tokens])
                     :total-tokens (+ (get-in response [:usage :input_tokens])
                                      (get-in response [:usage :output_tokens]))}}))

(defn normalize-openai-response
  "Transforms OpenAI API response to LLMResponse schema.
  Validates the result, throwing if OpenAI returns unexpected shape."
  [response]
  (m/coerce schema/LLMResponse
            {:text (get-in response [:choices 0 :message :content])
             :usage {:input-tokens (get-in response [:usage :prompt_tokens])
                     :output-tokens (get-in response [:usage :completion_tokens])
                     :total-tokens (get-in response [:usage :total_tokens])}}))

(defn normalize-gemini-response
  "Transforms Gemini API response to LLMResponse schema.
  Validates the result, throwing if Gemini returns unexpected shape."
  [response]
  (m/coerce schema/LLMResponse
            {:text (get-in response [:candidates 0 :content :parts 0 :text])
             :usage {:input-tokens (get-in response [:usageMetadata :promptTokenCount])
                     :output-tokens (get-in response [:usageMetadata :candidatesTokenCount])
                     :total-tokens (get-in response [:usageMetadata :totalTokenCount])}}))

(def response-normalizers
  "Maps provider keywords to functions that transform provider-specific
  response shapes to LLMResponse schema."
  {:anthropic normalize-anthropic-response
   :openai normalize-openai-response
   :google normalize-gemini-response})

(defmulti fetch-raw-provider-response
  "Returns promise of unnormalized, provider-specific response (JSON→CLJS only).
  Separated from normalization to model the external API boundary explicitly
  and enable integration tests to validate mock fixtures against real APIs."
  (fn [_messages model _api-key] (schema/model->provider model)))

(defmethod fetch-raw-provider-response :anthropic
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

(defmethod fetch-raw-provider-response :openai
  [messages model api-key]
  (let [request-body {:model model
                      :max_completion_tokens 8192
                      :messages (messages->openai-format messages)}
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}]
    (-> (js/fetch "https://api.openai.com/v1/chat/completions"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))

(defmethod fetch-raw-provider-response :google
  [messages model api-key]
  (let [request-body {:contents (messages->gemini-format messages)
                      :generationConfig {:maxOutputTokens 8192}}
        headers {"Content-Type" "application/json"}
        url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                 model ":generateContent?key=" api-key)]
    (js/console.log "[chat:request:gemini]"
                    (clj->js {:contents (count (:contents request-body))
                              :parts (count (get-in request-body [:contents 0 :parts]))}))
    (-> (js/fetch url
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))

(defn ^LLMResponse query-llm-provider
  "Queries LLM provider and returns normalized LLMResponse.
  Composes fetch-raw-provider-response + response-normalizers.

  Uses direct fetch instead of provider SDKs for simplicity—our use case is
  straightforward message exchange. Resist adding features (error handling,
  retries, streaming, etc.). Upgrade to SDKs when requirements outgrow this."
  [messages model api-key]
  (let [provider (schema/model->provider model)]
    (.then (fetch-raw-provider-response messages model api-key)
           (response-normalizers provider))))
