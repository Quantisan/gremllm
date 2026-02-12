(ns gremllm.schema.codec
  (:require [camel-snake-kebab.core :as csk]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ========================================
;; IPC Codecs
;; ========================================

(defn messages-from-ipc
  [messages-js]
  (as-> messages-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce schema/Messages $ mt/json-transformer)))

(def FlatSecrets
  "Secrets structure as received from IPC (main process).
   Flat map with provider-specific key names.
   Derived from provider-storage-key-map."
  (into [:map]
        (map (fn [[_provider storage-key]]
               [storage-key {:optional true} [:maybe :string]])
             schema/provider-storage-key-map)))

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
  (m/coerce schema/NestedSecrets
            {:api-keys (into {}
                             (keep (fn [provider]
                                     (when-let [value (get flat-secrets (schema/provider->api-key-keyword provider))]
                                       [provider value]))
                                   schema/supported-providers))}
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

(defn topic-id-from-ipc
  "Validates topic identifier received via IPC. Throws if invalid."
  [topic-id]
  (m/coerce schema/TopicId (js->clj topic-id) mt/json-transformer))

(def WorkspaceSyncData
  "Schema for workspace data sent from main to renderer via IPC.
   Used when loading a workspace folder from disk."
  [:map
   [:workspace [:map [:name :string]]]
   [:topics {:default {}} schema/WorkspaceTopics]
   [:document {:default {:content nil}} [:map [:content [:maybe :string]]]]])

(defn topic-to-ipc [topic-clj]
  (-> (m/coerce schema/Topic topic-clj mt/json-transformer)
      (clj->js)))

(defn topic-from-ipc
  "Transforms topic data from IPC into internal Topic schema. Throws if invalid."
  [topic-js]
  (as-> topic-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce schema/Topic $ mt/json-transformer)))

(defn workspace-sync-from-ipc
  "Validates and transforms workspace sync data from IPC. Throws if invalid."
  [workspace-data-js]
  (as-> workspace-data-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce WorkspaceSyncData $ mt/json-transformer)))

(defn workspace-sync-for-ipc
  "Validates and prepares workspace sync data for IPC transmission. Throws if invalid."
  [topics workspace document]
  (m/coerce WorkspaceSyncData
            {:topics topics :workspace workspace :document document}
            mt/strip-extra-keys-transformer))

;; ========================================
;; ACP Session Updates
;; ========================================

(def acp-chunk->message-type
  "Maps ACP content chunk session-update types to internal MessageType.
   Only includes session updates that produce chat messages."
  {:agent-message-chunk :assistant
   :agent-thought-chunk :reasoning})

(defn acp-update-text
  "Extracts text content from an ACP update chunk.

   update: AcpUpdate

   Returns nil for update types without text content.
   TODO: If AcpUpdate becomes a Record, convert to protocol getter."
  [update]
  (get-in update [:content :text]))

;; ACP Update sub-schemas
(def AcpCommandInput
  "Optional input schema for ACP commands."
  [:map
   [:hint {:optional true} :string]])

(def AcpCommand
  "Schema for an ACP command definition."
  [:map
   [:name :string]
   [:description :string]
   [:input {:optional true} [:maybe AcpCommandInput]]])

(def AcpTextContent
  "Schema for text content in ACP chunks."
  [:map
   [:type [:= "text"]]
   [:text :string]
   [:annotations {:optional true} [:maybe :any]]
   [:_meta {:optional true} [:maybe [:map-of :keyword :any]]]])

(def AcpUpdate
  "Discriminated union of ACP session update types.
   Dispatches on :session-update field."
  [:multi {:dispatch (fn [m]
                       ;; Convert raw string/keyword to kebab-case keyword for dispatch.
                       ;; Dispatch runs BEFORE transformers, expects raw keys:
                       ;; - \"sessionUpdate\" from ACP JS module (camelCase string)
                       ;; - \"session-update\" over IPC from main (kebab string via clj->js)
                       ;; - :session-update when data already in CLJS form (internal validation, tests)
                       (some-> (or (:session-update m)
                                   (get m "sessionUpdate")
                                   (get m "session-update"))
                               csk/->kebab-case
                               keyword))}
   [:available-commands-update
    [:map
     [:session-update [:= :available-commands-update]]
     [:available-commands [:vector AcpCommand]]]]

   [:agent-thought-chunk
    [:map
     [:session-update [:= :agent-thought-chunk]]
     [:content AcpTextContent]]]

   [:agent-message-chunk
    [:map
     [:session-update [:= :agent-message-chunk]]
     [:content AcpTextContent]]]])

(def AcpSessionUpdate
  "Schema for session updates from ACP."
  [:map
   [:acp-session-id :string] ;; TODO: :uuid type
   [:update AcpUpdate]])

(def ^:private acp-key-transformer
  "Maps sessionId → :acp-session-id, otherwise camel→kebab."
  (mt/key-transformer
    {:decode (fn [k]
               (if (= k "sessionId")
                 :acp-session-id
                 (csk/->kebab-case-keyword k)))}))

(def ^:private session-update-value-transformer
  "Transforms :session-update string values to kebab-case keywords."
  (mt/transformer
    {:name :session-update
     :decoders {:map (fn [x]
                       (if (and (map? x) (string? (:session-update x)))
                         (update x :session-update (comp keyword csk/->kebab-case))
                         x))}}))

(defn acp-session-update-from-js
  "Coerce ACP session update from JS dispatcher bridge."
  [js-data]
  ;; Keep this pure Malli; dispatch handles raw keys before transformer runs.
  (m/coerce AcpSessionUpdate
            (js->clj js-data)
            (mt/transformer
              acp-key-transformer
              session-update-value-transformer
              mt/json-transformer)))

(defn acp-session-update-from-ipc
  "Validates and transforms ACP session update from IPC. Throws if invalid."
  [event-data-js]
  (acp-session-update-from-js event-data-js))
