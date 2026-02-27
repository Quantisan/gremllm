(ns gremllm.schema.codec
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ========================================
;; IPC Codecs
;; ========================================

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
   :agent-thought-chunk :reasoning
   :tool-call           :tool-use})

(defn acp-update-text
  "Extracts text content from an ACP update chunk.

   update: AcpUpdate

   Returns nil for update types without text content.
   TODO: If AcpUpdate becomes a Record, convert to protocol getter."
  [update]
  (get-in update [:content :text]))

(defn acp-read-display-label
  "Extracts display text from a Read tool-call-update with tool-response metadata.
   Returns 'Read — filename (N lines)' or nil."
  [update]
  (when-let [file (get-in update [:meta :claude-code :tool-response :file])]
    (let [filename (-> (:filePath file) (str/split #"/") last)
          lines   (:totalLines file)]
      (str "Read — " filename " (" lines " lines)"))))

(defn acp-pending-diffs
  "Extracts diff items from a tool-call-update's content.
   Returns a vector of diff maps or nil if none present."
  [{:keys [content]}]
  (when (seq content)
    (let [diffs (filterv #(= "diff" (:type %)) content)]
      (when (seq diffs) diffs))))

(defn read-tool-response?
  "True when a tool-call-update carries Read tool-response metadata."
  [update]
  (some? (get-in update [:meta :claude-code :tool-response :file])))

(defn has-diffs?
  "True when a tool-call-update contains diff content."
  [{:keys [content]}]
  (some #(= "diff" (:type %)) content))

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

(def AcpToolLocation
  "Schema for ACP tool call file locations."
  [:map
   [:path :string]
   [:line {:optional true} :int]])

(def AcpToolMetaClaudeCode
  "Schema for ACP metadata emitted by Claude Code tools."
  [:map
   [:tool-name {:optional true} :string]])

(def AcpToolMeta
  "Schema for ACP tool call metadata."
  [:map
   [:claude-code {:optional true} AcpToolMetaClaudeCode]])

(def AcpToolRawInput
  "Schema for ACP tool call raw input. Open map — different tools send different
   keys (e.g. read sends file-path; edit sends old-string, new-string, file-path)."
  [:map])

(def AcpWrappedContent
  "Wrapped text content block as sent by ACP in tool call content arrays."
  [:map
   [:type [:= "content"]]
   [:content AcpTextContent]])

(def AcpTerminalContent
  "Terminal output content in ACP tool call content arrays."
  [:map
   [:type [:= "terminal"]]
   [:terminal-id :string]])

(def AcpDiffItem
  "Structured diff content from a tool-call-update write operation.
   old-text is optional — absent when ACP creates a new file."
  [:map
   [:type [:= "diff"]]
   [:path :string]
   [:old-text {:optional true} :string]
   [:new-text :string]])

(def AcpToolCallContentItem
  "Discriminated union of ACP tool call content blocks.
   Dispatches on :type: content (wrapped text), diff (file diffs), terminal (output).
   Both tool-call and tool-call-update share this content type."
  [:multi {:dispatch (fn [m] (some-> (:type m) name keyword))}
   [:content  AcpWrappedContent]
   [:diff     AcpDiffItem]
   [:terminal AcpTerminalContent]])

(def AcpRawOutputItem
  "Raw output items in tool-call-update (unified diff text blocks)."
  AcpTextContent)

(def AcpToolCallContent
  "Schema for ACP tool call content."
  [:vector AcpToolCallContentItem])

(def AcpUpdate
  "Discriminated union of ACP session update types.
   Dispatches on :session-update field."
  [:multi {:dispatch (fn [m]
                       ;; Accept both normalized and raw bridge keys.
                       ;; Most paths normalize keys before coercion, but tests and
                       ;; internal callers may pass pre-coerced CLJS maps directly.
                       (some-> (or (:session-update m)
                                   (:sessionUpdate m))
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
     [:content AcpTextContent]]]

   [:tool-call
    [:map
     [:session-update                    [:= :tool-call]]
     [:tool-call-id                      :string]
     [:title                             :string]
     [:kind                              [:enum "read" "edit"]]
     [:status                            :string]
     [:raw-input                         AcpToolRawInput]
     [:meta {:optional true}             AcpToolMeta]
     [:content                           AcpToolCallContent]
     [:locations {:optional true}        [:vector AcpToolLocation]]]]

   [:tool-call-update
    [:map
     [:session-update              [:= :tool-call-update]]
     [:tool-call-id                :string]
     [:status {:optional true}     :string]
     [:raw-output {:optional true} [:or :string [:vector AcpRawOutputItem]]]
     [:content {:optional true}    [:vector AcpToolCallContentItem]]
     [:locations {:optional true}  [:vector AcpToolLocation]]
     [:meta {:optional true}       AcpToolMeta]]]])

(def AcpSessionUpdate
  "Schema for session updates from ACP."
  [:map
   [:acp-session-id :string] ;; TODO: :uuid type
   [:update AcpUpdate]])

(def ^:private acp-key-transformer
  "Transforms ACP keys from camelCase to kebab-case keywords.
   Specially handles sessionId → :acp-session-id."
  (mt/key-transformer
    {:decode (fn [k]
               (if (= k :sessionId)
                 :acp-session-id
                 (csk/->kebab-case-keyword k)))}))

(def ^:private session-update-value-transformer
  "Transforms :session-update values to kebab-case keywords."
  (mt/transformer
    {:name :session-update
     :decoders {:map (fn [m]
                       (cond-> m
                         (:session-update m)
                         (update :session-update
                                 (comp keyword csk/->kebab-case name))))}}))


(defn acp-session-update-from-js
  "Coerce ACP session update from JS dispatcher bridge."
  [js-data]
  (m/coerce AcpSessionUpdate
            (js->clj js-data :keywordize-keys true)
            (mt/transformer
              acp-key-transformer
              session-update-value-transformer
              mt/json-transformer)))

(defn acp-session-update-from-ipc
  "Validates and transforms ACP session update from IPC. Throws if invalid."
  [event-data-js]
  (acp-session-update-from-js event-data-js))
