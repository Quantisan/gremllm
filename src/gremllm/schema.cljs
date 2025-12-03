(ns gremllm.schema
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

(def supported-models
  "Canonical map of supported LLM models. Keys are model IDs, values are display names."
  {"claude-sonnet-4-5-20250929" "Claude 4.5 Sonnet"
   "claude-opus-4-1-20250805"   "Claude 4.1 Opus"
   "claude-haiku-4-5-20251001"  "Claude 4.5 Haiku"
   "gpt-5"                      "GPT-5"
   "gpt-5-mini"                 "GPT-5 Mini"
   "gemini-2.5-flash"           "Gemini 2.5 Flash"
   "gemini-2.5-pro"             "Gemini 2.5 Pro"})

;; ========================================
;; Messages
;; ========================================

(def AttachmentRef
  "Reference to a stored attachment file.
   Persisted in topic EDN, not the actual file content."
  [:map
   [:ref :string]        ; Hash prefix (first 8 chars of SHA256)
   [:name :string]       ; Original filename
   [:mime-type :string]  ; MIME type (e.g., 'image/png')
   [:size :int]])        ; File size in bytes

(def APIAttachment
  "Attachment in API provider format (with base64 data).
   Validated at filesystem→API boundary."
  [:map
   [:mime-type :string]
   [:data :string]])     ; base64-encoded content

(defn attachment-ref->api-format
  "Transform AttachmentRef + content to validated API format.
   Takes AttachmentRef and Node Buffer, returns validated APIAttachment.
   Throws if schema invalid."
  [attachment-ref content-buffer]
  (let [api-attachment {:mime-type (:mime-type attachment-ref)
                        :data (.toString content-buffer "base64")}]
    (m/coerce APIAttachment api-attachment mt/json-transformer)))

(def Message
  [:map
   [:id :int]
   [:type [:enum :user :assistant]]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]])

(def LLMResponse
  "Normalized LLM response shape, independent of provider.
   Main process transforms provider responses to this format before IPC."
  [:map
   [:text :string]
   [:usage [:map
            [:input-tokens :int]
            [:output-tokens :int]
            [:total-tokens :int]]]])

(def Messages
  [:vector Message])

(def Model
  "Valid LLM model identifier"
  (into [:enum] (keys supported-models)))

(def AttachmentPaths
  "Vector of absolute file path strings for attachments"
  [:vector [:string {:min 1}]])

(defn messages-from-ipc
  [messages-js]
  (as-> messages-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce Messages $ mt/json-transformer)))

(defn messages->chat-api-format
  "Converts internal message format to Chat API format for LLM providers.
  Internal: {:id, :type :user|:assistant, :text, :attachments?}
  Chat API: {:role 'user'|'assistant', :content, :attachments?}"
  [messages]
  (mapv (fn [{:keys [type text attachments]}]
          (cond-> {:role (if (= type :user) "user" "assistant")
                   :content text}
            attachments (assoc :attachments attachments)))
        messages))

(defn model-from-ipc
  [model-js]
  (m/coerce Model (js->clj model-js) mt/json-transformer))

(defn attachment-paths-from-ipc
  [attachment-paths-js]
  (when attachment-paths-js
    (as-> attachment-paths-js $
      (js->clj $)
      (m/coerce AttachmentPaths $ mt/json-transformer))))

(defn messages-to-ipc
  "Validates messages and converts to JS for IPC transmission. Throws if invalid."
  [messages]
  (-> (m/coerce Messages messages mt/json-transformer)
      (clj->js)))

(defn model-to-ipc
  "Validates model and converts to JS for IPC transmission. Throws if invalid."
  [model]
  (-> (m/coerce Model model mt/json-transformer)
      (clj->js)))

(defn attachment-paths-to-ipc
  "Validates attachment paths and converts to JS for IPC transmission. Throws if invalid."
  [attachment-paths]
  (when attachment-paths
    (-> (m/coerce AttachmentPaths attachment-paths mt/json-transformer)
        (clj->js))))

;; ========================================
;; Providers
;; ========================================

(def provider-storage-key-map
  "Canonical mapping of provider keywords to their storage key names.
   Single source of truth for provider-to-storage-key relationships."
  {:anthropic :anthropic-api-key
   :openai    :openai-api-key
   :google    :gemini-api-key})

(def supported-providers
  "Canonical list of supported LLM providers.
   Derived from provider-storage-key-map for single source of truth."
  (vec (keys provider-storage-key-map)))

(defn model->provider
  "Infers provider from model string. Pure function for easy testing."
  [model]
  (cond
    (str/starts-with? model "claude-") :anthropic
    (str/starts-with? model "gpt-")    :openai
    (str/starts-with? model "gemini-") :google
    :else (throw (js/Error. (str "Unknown provider for model: " model)))))

(defn provider-display-name
  "Returns human-readable display name for provider keyword."
  [provider]
  (case provider
    :anthropic "Anthropic"
    :openai    "OpenAI"
    :google    "Google"))

(defn provider->api-key-keyword
  "Maps provider to safeStorage lookup key. Pure function for easy testing."
  [provider]
  (get provider-storage-key-map provider))

(defn keyword-to-provider
  "Inverse of provider->api-key-keyword. Maps storage keyword to provider.
   :anthropic-api-key → :anthropic
   :openai-api-key → :openai
   :gemini-api-key → :google"
  [storage-keyword]
  (or (get (set/map-invert provider-storage-key-map) storage-keyword)
      (throw (js/Error. (str "Unknown API key keyword: " storage-keyword)))))

(defn models-by-provider
  "Groups supported-models by provider. Returns map of {provider-name [model-ids]}."
  []
  (->> supported-models
       keys
       (group-by model->provider)
       (map (fn [[provider models]]
              [(provider-display-name provider) (vec models)]))
       (into (sorted-map))))

;; ========================================
;; Secrets
;; ========================================

(def APIKeysMap
  "Nested map of provider keywords to redacted API key strings.
   Used in renderer state at [:system :secrets :api-keys]"
  [:map-of
   (into [:enum] supported-providers)
   [:maybe :string]])

(def NestedSecrets
  "Secrets structure used in renderer state after transformation.
   Contains nested :api-keys map plus any other secret entries."
  [:map
   [:api-keys {:optional true} APIKeysMap]])

(def FlatSecrets
  "Secrets structure as received from IPC (main process).
   Flat map with provider-specific key names.
   Derived from provider-storage-key-map."
  (into [:map]
        (map (fn [[_provider storage-key]]
               [storage-key {:optional true} [:maybe :string]])
             provider-storage-key-map)))

(def SystemInfo
  "System info structure as received from main process.
   Contains platform capabilities and secrets."
  [:map
   [:encryption-available? :boolean]
   [:secrets {:optional true} FlatSecrets]])

(defn secrets-from-ipc
  "Transforms flat IPC secrets to nested api-keys structure. Throws if invalid.
   {:anthropic-api-key 'sk-ant-xyz'} → {:api-keys {:anthropic 'sk-ant-xyz'}}"
  [flat-secrets]
  (m/coerce NestedSecrets
    {:api-keys (into {}
                 (keep (fn [provider]
                         (when-let [value (get flat-secrets (provider->api-key-keyword provider))]
                           [provider value]))
                       supported-providers))}
    mt/json-transformer))

(defn system-info-from-ipc
  "Validates system info from IPC and transforms secrets. Throws if invalid."
  [system-info-js]
  (as-> system-info-js $
    (js->clj $ :keywordize-keys true)
    (if (:secrets $)
      (update $ :secrets secrets-from-ipc)
      $)
    (m/coerce SystemInfo $ mt/json-transformer)))

(defn system-info-to-ipc
  "Validates and prepares system info for IPC transmission. Throws if invalid."
  [system-info]
  (m/coerce SystemInfo system-info mt/strip-extra-keys-transformer))


;; ========================================
;; Topics & Workspaces
;; ========================================

(defn generate-topic-id []
  ;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
  ;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

(def PersistedTopic
  "Schema for topics as saved to disk"
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"} :string]
   [:model {:default "gemini-2.5-flash"} ;; defaulting to Flash because is cheap and fast
    (into [:enum] (keys supported-models))]
   [:messages {:default []} [:vector Message]]])

(def Topic
  "Schema for topics in app state (includes transient fields)"
  (mu/merge
    PersistedTopic
    [:map
     [:unsaved? {:optional true} :boolean]]))

;; TODO: refactor with (generate-topic-id)
(def TopicId
  "Schema for topic identifiers shared across IPC boundaries."
  [:string {:min 1}])

(defn topic-id-from-ipc
  "Validates topic identifier received via IPC. Throws if invalid."
  [topic-id]
  (m/coerce TopicId (js->clj topic-id) mt/json-transformer))

(defn create-topic []
  (m/decode Topic {} mt/default-value-transformer))

(def WorkspaceTopics
  "Map of Topics keyed by Topic ID"
  [:map-of :string Topic])

(defn valid-workspace-topics? [topics-map]
  (m/validate WorkspaceTopics topics-map))

(def WorkspaceSyncData
  "Schema for workspace data sent from main to renderer via IPC.
   Used when loading a workspace folder from disk."
  [:map
   [:workspace [:map [:name :string]]]
   [:topics {:default {}} WorkspaceTopics]])

(defn create-workspace-meta
  "Constructor for workspace metadata kept at [:workspace] and sent over IPC."
  [name]
  {:name name})

(defn topic-from-ipc
  "Transforms topic data from IPC into internal Topic schema. Throws if invalid."
  [topic-js]
  (as-> topic-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce Topic $ mt/json-transformer)))

(defn workspace-sync-from-ipc
  "Validates and transforms workspace sync data from IPC. Throws if invalid."
  [workspace-data-js]
  (as-> workspace-data-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce WorkspaceSyncData $ mt/json-transformer)))

(defn workspace-sync-for-ipc
  "Validates and prepares workspace sync data for IPC transmission. Throws if invalid."
  [topics workspace]
  (m/coerce WorkspaceSyncData
            {:topics topics
             :workspace workspace}
            mt/strip-extra-keys-transformer))

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format.
  Throws if the topic data is invalid."
  (m/coercer Topic mt/json-transformer))

(def topic-for-disk
  "Prepares topic for disk persistence, stripping transient fields. Throws if invalid."
  (m/coercer PersistedTopic mt/strip-extra-keys-transformer))
