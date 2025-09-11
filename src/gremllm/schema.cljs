(ns gremllm.schema
  (:require [malli.core :as m]
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

(def PersistedTopic
  "Schema for topics as saved to disk"
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"} :string]
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
   [:path :string]
   [:topics {:default {}} WorkspaceTopics]])

;; Coercion helpers for boundaries
(def topic-from-ipc
  "Transforms topic data received via IPC into internal Topic schema.
  Used when renderer receives topic data from main process."
  (m/decoder Topic mt/string-transformer))

(def workspace-sync-from-ipc
  "Validates and transforms workspace sync data from IPC. Throws if invalid."
  (m/coercer WorkspaceSyncData mt/json-transformer))

(def workspace-sync-for-ipc
  "Strips extra keys from workspace sync data for IPC transmission."
  (m/encoder WorkspaceSyncData mt/strip-extra-keys-transformer))

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format.
  Throws if the topic data is invalid."
  (m/coercer Topic mt/json-transformer))

(def topic-for-disk
  "Prepares a topic for disk persistence, stripping transient fields."
  (m/encoder PersistedTopic mt/strip-extra-keys-transformer))
