(ns gremllm.schema
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

(defn generate-topic-id []
  ;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
  ;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

(def Message
  [:map
   [:id :int]
   [:type [:enum :user :assistant]]
   [:text :string]])

(def LLMResponse
  "Normalized LLM response shape, independent of provider.
   Main process transforms provider responses to this format before IPC."
  [:map
   [:text :string]
   [:usage [:map
            [:input-tokens :int]
            [:output-tokens :int]
            [:total-tokens :int]]]])

(def supported-models
  "Canonical map of supported LLM models. Keys are model IDs, values are display names."
  {"claude-sonnet-4-5-20250929" "Claude 4.5 Sonnet"
   "claude-opus-4-1-20250805"   "Claude 4.1 Opus"
   "claude-haiku-4-5-20251001"  "Claude 4.5 Haiku"
   "gpt-5"                      "GPT-5"
   "gpt-5-mini"                 "GPT-5 Mini"
   "gemini-2.5-flash"           "Gemini 2.5 Flash"
   "gemini-2.5-pro"             "Gemini 2.5 Pro"})

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

(defn models-by-provider
  "Groups supported-models by provider. Returns map of {provider-name [model-ids]}."
  []
  (->> supported-models
       keys
       (group-by model->provider)
       (map (fn [[provider models]]
              [(provider-display-name provider) (vec models)]))
       (into (sorted-map))))

(def PersistedTopic
  "Schema for topics as saved to disk"
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"} :string]
   [:model {:default "claude-sonnet-4-5-20250929"} (into [:enum] (keys supported-models))]
   [:messages {:default []} [:vector Message]]])

(def Topic
  "Schema for topics in app state (includes transient fields)"
  (mu/merge
    PersistedTopic
    [:map
     [:unsaved? {:optional true} :boolean]]))

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

;; Coercion helpers for boundaries
(defn topic-from-ipc
  "Transforms topic data received via IPC into internal Topic schema.
  Used when renderer receives topic data from main process."
  [topic-js]
  (as-> topic-js $
    (js->clj $ :keywordize-keys true)
    (m/decode Topic $ mt/string-transformer)))

(defn workspace-sync-from-ipc
  "Validates and transforms workspace sync data from IPC. Throws if invalid."
  [workspace-data-js]
  (as-> workspace-data-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce WorkspaceSyncData $ mt/json-transformer)))

(defn workspace-sync-for-ipc
  "Prepares workspace sync data for IPC transmission, including workspace metadata."
  [topics workspace]
  (m/encode WorkspaceSyncData
            {:topics topics
             :workspace workspace}
            mt/strip-extra-keys-transformer))

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format.
  Throws if the topic data is invalid."
  (m/coercer Topic mt/json-transformer))

(def topic-for-disk
  "Prepares a topic for disk persistence, stripping transient fields."
  (m/encoder PersistedTopic mt/strip-extra-keys-transformer))
