(ns gremllm.schema.codec
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ========================================
;; Disk Codecs
;; ========================================

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format. Applies defaults for fields added after
  initial save. Throws if the topic data is invalid."
  (m/coercer schema/Topic (mt/transformer mt/default-value-transformer mt/json-transformer)))

(def topic-for-disk
  "Prepares topic for disk persistence, stripping transient fields.
  Applies defaults for any fields missing from in-memory topic. Throws if invalid."
  (m/coercer schema/PersistedTopic (mt/transformer mt/default-value-transformer mt/strip-extra-keys-transformer)))

;; ========================================
;; IPC Codecs
;; ========================================

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

(defn user-message-from-ipc
  "Transforms structured user message data from IPC into internal Message schema.
   Throws if invalid."
  [message-js]
  (as-> message-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce schema/Message $ mt/json-transformer)))

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
;; Excerpt (Selection Capture)
;; ========================================

(defn- rect-from-dom [r]
  {:top (.-top r) :left (.-left r) :width (.-width r) :height (.-height r)})

(defn- range-from-dom [range]
  {:start-container (.. range -startContainer -nodeName)
   :start-text      (.. range -startContainer -textContent)
   :start-offset    (.-startOffset range)
   :end-container   (.. range -endContainer -nodeName)
   :end-text        (.. range -endContainer -textContent)
   :end-offset      (.-endOffset range)
   :common-ancestor (.. range -commonAncestorContainer -nodeName)
   :bounding-rect   (rect-from-dom (.getBoundingClientRect range))
   :client-rects    (mapv rect-from-dom (array-seq (.getClientRects range)))})

(defn captured-selection-from-dom
  "Reads a live js/Selection and coerces into CapturedSelection. Throws if invalid."
  [sel]
  (m/coerce schema/CapturedSelection
            {:text          (.toString sel)
             :range-count   (.-rangeCount sel)
             :anchor-node   (.. sel -anchorNode -nodeName)
             :anchor-offset (.-anchorOffset sel)
             :focus-node    (.. sel -focusNode -nodeName)
             :focus-offset  (.-focusOffset sel)
             :range         (range-from-dom (.getRangeAt sel 0))}
            mt/json-transformer))

(defn anchor-context-from-dom
  "Reads a live DOM panel element and coerces into AnchorContext. Throws if invalid."
  [panel]
  (m/coerce schema/AnchorContext
            {:panel-rect       (rect-from-dom (.getBoundingClientRect panel))
             :panel-scroll-top (.-scrollTop panel)}
            mt/json-transformer))

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

(defn tool-response-diffs
  "Extracts diff items from a PostToolUse tool-call-update's content.
   Returns a vector of diff maps or nil if none present.
   Excludes streaming refinement events which carry :kind."
  [{:keys [session-update content kind]}]
  (when (and (= :tool-call-update session-update)
             (nil? kind)
             (seq content))
    (let [diffs (filterv #(= "diff" (:type %)) content)]
      (when (seq diffs) diffs))))

(defn tool-response-read-event?
  "True when a tool-call-update is a Read response event."
  [{:keys [session-update] :as update}]
  (and (= :tool-call-update session-update)
       (= "Read" (get-in update [:meta :claude-code :tool-name]))))

(defn tool-response-read-with-file-metadata?
  "True when a Read tool-call-update carries tool-response file metadata."
  [{:keys [session-update] :as update}]
  (and (= :tool-call-update session-update)
       (some? (get-in update [:meta :claude-code :tool-response :file]))))

(defn tool-response-has-diffs?
  "True when a tool-call-update is a PostToolUse event containing diff content.
   Excludes streaming refinement events which carry :kind."
  [{:keys [session-update content kind]}]
  (and (= :tool-call-update session-update)
       (nil? kind)
       (some #(= "diff" (:type %)) content)))

(defn tool-call-update-status
  "Extracts the status string from a tool-call-update, or nil."
  [{:keys [session-update status]}]
  (when (= :tool-call-update session-update)
    status))

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

;; ========================================
;; ACP Permission Request
;; ========================================

(def AcpPermissionOptionKind
  "ACP permission option kinds."
  [:enum "allow_always" "allow_once" "reject_once" "reject_always"])

(def AcpPermissionOption
  "A single permission option presented to the client."
  [:map
   [:option-id :string]
   [:name      :string]
   [:kind      AcpPermissionOptionKind]])

(def AcpToolKind
  "ACP tool operation kinds."
  [:enum "read" "edit" "delete" "move" "search" "execute" "think" "fetch" "switch_mode" "other"])

(def AcpPermissionToolCall
  "Tool call context within an ACP permission request."
  [:map
   [:tool-call-id              :string]
   [:tool-name {:optional true} :string]
   [:kind         [:maybe AcpToolKind]]
   [:title        :string]
   [:raw-input    [:map-of :keyword :any]]
   [:locations    [:vector AcpToolLocation]]])

(def AcpPermissionRequest
  "ACP requestPermission params shape."
  [:map
   [:acp-session-id :string]
   [:tool-call      AcpPermissionToolCall]
   [:options        [:vector AcpPermissionOption]]])

(defn acp-permission-request-from-js
  "Coerce ACP requestPermission params from JS callback."
  [js-data]
  (m/coerce AcpPermissionRequest
            (js->clj js-data :keywordize-keys true)
            (mt/transformer
              acp-key-transformer
              mt/json-transformer)))

(defn acp-permission-outcome-to-js
  "Coerce a resolved permission outcome to ACP SDK wire shape.
   Inbound: {:outcome {:outcome \"selected\" :option-id \"...\"}}
         or {:outcome {:outcome \"cancelled\"}}
   Returns a JS object with camelCase optionId as the SDK expects."
  [{{:keys [outcome option-id]} :outcome}]
  (case outcome
    "cancelled" #js {:outcome #js {:outcome "cancelled"}}
    "selected"  #js {:outcome #js {:outcome "selected" :optionId option-id}}))
