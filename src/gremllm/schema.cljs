(ns gremllm.schema
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

;; ========================================
;; Messages
;; ========================================

(def MessageType
  "Valid message type identifiers."
  [:enum :user :assistant :reasoning :tool-use])

(def AttachmentRef
  "Reference to a stored attachment file.
   Persisted in topic EDN, not the actual file content."
  [:map
   [:ref :string]        ; Hash prefix (first 8 chars of SHA256)
   [:name :string]       ; Original filename
   [:mime-type :string]  ; MIME type (e.g., 'image/png')
   [:size :int]])        ; File size in bytes

(def Message
  [:map
   [:id :int]
   [:type MessageType]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]])

(def Messages
  [:vector Message])

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
   [:acp-session-id {:optional true} :string]         ;; TODO: refator to :uuid type
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

(defn create-topic []
  (m/decode Topic {} mt/default-value-transformer))

;; TODO: rename to DocumentTopics
(def WorkspaceTopics
  "Map of Topics keyed by Topic ID"
  [:map-of :string Topic])

(defn valid-workspace-topics? [topics-map]
  (m/validate WorkspaceTopics topics-map))

(defn create-workspace-meta
  "Constructor for workspace metadata kept at [:workspace] and sent over IPC."
  [name]
  {:name name})

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format.
  Throws if the topic data is invalid."
  (m/coercer Topic mt/json-transformer))

(def topic-for-disk
  "Prepares topic for disk persistence, stripping transient fields. Throws if invalid."
  (m/coercer PersistedTopic mt/strip-extra-keys-transformer))
