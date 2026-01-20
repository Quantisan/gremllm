(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations"
  (:require [clojure.string :as str]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(defn- attachment->gemini-part
  "Transform a single attachment to Gemini inline_data part."
  [{:keys [mime-type data]}]
  {:inline_data {:mime_type mime-type
                 :data data}})

(defn- message->gemini-format
  "Transform a single message to Gemini contents format."
  [{:keys [role content attachments]}]
  {:role (if (= role "assistant") "model" role)
   :parts (concat
            (mapv attachment->gemini-part (or attachments []))
            (when-not (str/blank? content)
              [{:text content}]))})

(defn messages->gemini-format
  "Transform OpenAI/Anthropic message format to Gemini contents format.
  Maps {:role 'assistant' :content 'text'} to {:role 'model' :parts [{:text 'text'}]}.
  Supports optional :attachments for multimodal messages with inline_data.
  Pure function for easy testing."
  [messages]
  (let [result (mapv message->gemini-format messages)]
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

(def ^:private text-mime-types
  "MIME types that should be converted to text parts instead of file parts."
  #{"text/markdown" "text/plain"})

(def ^:private image-mime-types
  "MIME types that Anthropic accepts as image content blocks."
  #{"image/png" "image/jpeg" "image/gif" "image/webp"})

(def ^:private document-mime-types
  "MIME types that Anthropic accepts as document content blocks."
  #{"application/pdf"})

(defn- attachment->text-block
  "Convert text attachment to text content block (shared by OpenAI/Anthropic)."
  [{:keys [data filename]}]
  {:type "text"
   :text (str "Attachment" (when filename (str " (" filename ")"))
              ":\n\n"
              (.toString (js/Buffer.from data "base64") "utf8"))})

(defn- attachment->openai-part
  "Transform a single attachment to OpenAI multimodal part.
   Text types become {:type 'text'}, others become {:type 'file'}."
  [{:keys [mime-type data filename] :as attachment}]
  (if (text-mime-types mime-type)
    (attachment->text-block attachment)
    {:type "file"
     :file {:filename (or filename "attachment")
            :file_data (str "data:" mime-type ";base64," data)}}))

(defn- message->openai-format
  "Transform a single message to OpenAI multimodal format."
  [{:keys [role content attachments]}]
  (if (seq attachments)
    {:role role
     :content (into (mapv attachment->openai-part attachments)
                    (when-not (str/blank? content)
                      [{:type "text" :text content}]))}
    {:role role :content content}))

(defn messages->openai-format
  "Transform messages to OpenAI multimodal format.
   Pure function for easy testing."
  [messages]
  (let [result (mapv message->openai-format messages)
        first-content (:content (first result))]
    (js/console.log "[chat:transform:openai]"
                    (clj->js {:messages (count messages)
                              :parts (when (vector? first-content)
                                       (mapv (fn [part]
                                               (case (:type part)
                                                 "text" {:type "text"}
                                                 "file" {:type "file"
                                                         :filename (get-in part [:file :filename])}
                                                 {:type "unknown"}))
                                             first-content))}))
    result))

(defn- attachment->anthropic-part
  "Transform attachment to Anthropic content block.
   Images → image block, PDFs → document block, text → text block."
  [{:keys [mime-type data] :as attachment}]
  (cond
    (text-mime-types mime-type)
    (attachment->text-block attachment)

    (image-mime-types mime-type)
    {:type "image"
     :source {:type "base64"
              :media_type mime-type
              :data data}}

    (document-mime-types mime-type)
    {:type "document"
     :source {:type "base64"
              :media_type mime-type
              :data data}}))

(defn- message->anthropic-format
  "Transform a single message to Anthropic multimodal format."
  [{:keys [role content attachments]}]
  (if (seq attachments)
    {:role role
     :content (into (mapv attachment->anthropic-part attachments)
                    (when-not (str/blank? content)
                      [{:type "text" :text content}]))}
    {:role role :content content}))

(defn messages->anthropic-format
  "Transform messages to Anthropic multimodal format.
   Pure function for easy testing."
  [messages]
  (let [result (mapv message->anthropic-format messages)
        first-content (:content (first result))]
    (js/console.log "[chat:transform:anthropic]"
                    (clj->js {:messages (count messages)
                              :parts (when (vector? first-content)
                                       (mapv :type first-content))}))
    result))

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
  Validates the result, throwing if Anthropic returns unexpected shape.
  When extended thinking is enabled, extracts both thinking and text blocks."
  [response]
  (let [content (:content response)
        thinking-block (first (filter #(= "thinking" (:type %)) content))
        text-block (first (filter #(= "text" (:type %)) content))]
    (m/coerce schema/LLMResponse
              (cond-> {:text  (:text text-block)
                       :usage {:input-tokens (get-in response [:usage :input_tokens])
                               :output-tokens (get-in response [:usage :output_tokens])
                               :total-tokens (+ (get-in response [:usage :input_tokens])
                                                (get-in response [:usage :output_tokens]))}}
                thinking-block (assoc :thinking (:thinking thinking-block))))))

(defn normalize-openai-response
  "Transforms OpenAI API response to LLMResponse schema.
  Validates the result, throwing if OpenAI returns unexpected shape."
  [response]
  ;; TODO: Chat Completions only reports reasoning token counts (usage),
  ;; not reasoning content. Upgrade to Responses API to access reasoning items.
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

(defmulti response-normalizers
  "Transforms provider-specific response shapes to LLMResponse schema."
  (fn [provider _response] provider))

(defmethod response-normalizers :anthropic
  [_provider response]
  (normalize-anthropic-response response))

(defmethod response-normalizers :openai
  [_provider response]
  (normalize-openai-response response))

(defmethod response-normalizers :google
  [_provider response]
  (normalize-gemini-response response))

(defmulti fetch-raw-provider-response
  "Returns promise of unnormalized, provider-specific response (JSON→CLJS only).
  Separated from normalization to model the external API boundary explicitly
  and enable integration tests to validate mock fixtures against real APIs.
  The reasoning param enables extended thinking for Anthropic models and
  reasoning effort for OpenAI models."
  (fn [_messages model _api-key _reasoning] (schema/model->provider model)))

(defmethod fetch-raw-provider-response :anthropic
  [messages model api-key reasoning]
  (let [request-body (cond-> {:model model
                               ;; When reasoning is enabled, budget_tokens must be less than max_tokens.
                               ;; Using 16000 max_tokens with 10000 thinking budget to satisfy this constraint.
                              :max_tokens 16000
                              :messages (messages->anthropic-format messages)}
                       ;; Reference https://platform.claude.com/docs/en/build-with-claude/extended-thinking
                       reasoning (assoc :thinking {:type "enabled"
                                                   :budget_tokens 10000}))
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
  [messages model api-key reasoning]
  (let [request-body (cond-> {:model model
                              :max_completion_tokens 8192
                              :messages (messages->openai-format messages)}
                       ;; Reference https://platform.openai.com/docs/api-reference/chat/object
                       reasoning (assoc :reasoning_effort "high"))
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}]
    (-> (js/fetch "https://api.openai.com/v1/chat/completions"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))

(defmethod fetch-raw-provider-response :google
  [messages model api-key _reasoning]
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
  The reasoning param enables extended thinking for Anthropic models and
  reasoning effort for OpenAI models.

  Uses direct fetch instead of provider SDKs for simplicity—our use case is
  straightforward message exchange. Resist adding features (error handling,
  retries, streaming, etc.). Upgrade to SDKs when requirements outgrow this."
  [messages model api-key reasoning]
  (let [provider (schema/model->provider model)]
    (.then (fetch-raw-provider-response messages model api-key reasoning)
           #(response-normalizers provider %))))
