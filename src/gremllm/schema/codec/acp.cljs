(ns gremllm.schema.codec.acp
  "ACP (Agent Client Protocol) trust boundary — wire-to-CLJS coercion.
   Validates and transforms ACP session updates and permission requests."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]))

;; Design rules for this boundary:
;;   - Schemas track the consumer contract, not the SDK wire. A field exists
;;     only if a consumer reads it.
;;   - Open at the leaves ([::m/default :map] for unknown :type), strict
;;     where the resolver dispatches (e.g. permission :kind is required).
;;   - SDK variants we don't consume still need a dispatch-only entry
;;     (e.g. AcpUsageUpdate) so the coercion catch doesn't flood logs.
;;   - Upstream SDK pointers stay in this file (see block before AcpToolCall).

;; ========================================
;; ACP Session Updates
;; ========================================

(def acp-chunk->message-type
  "Maps streaming ACP text chunk session-update types to internal :type values."
  {:agent-message-chunk :assistant
   :agent-thought-chunk :reasoning})

(defn streaming-text-chunk?
  "True when the update is a streaming text chunk (assistant or reasoning)."
  [{:keys [session-update]}]
  (contains? acp-chunk->message-type session-update))

(defn- web-search? [update]
  (= "WebSearch" (get-in update [:meta :claude-code :tool-name])))

(defn web-search-started?
  "True when the update is the begin event for a WebSearch tool call."
  [{:keys [session-update] :as update}]
  (and (= :tool-call session-update)
       (web-search? update)))

(defn web-search-updated?
  "True when the update is a streaming refinement event for a WebSearch tool call."
  [{:keys [session-update] :as update}]
  (and (= :tool-call-update session-update)
       (web-search? update)))

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

(defn edit-diffs
  "Extracts diff items from an Edit/Write PostToolUse tool-call-update's content.
   Returns a vector of diff maps or nil if none present.
   Excludes streaming refinement events which carry :kind."
  [{:keys [session-update content kind]}]
  (when (and (= :tool-call-update session-update)
             (nil? kind)
             (seq content))
    (let [diffs (filterv #(= "diff" (:type %)) content)]
      (when (seq diffs) diffs))))

(defn permission-edit-diffs
  "Extracts diff items from an Edit/Write permission request's :tool-call :content.
   Returns a vector of diff maps or nil when none present.
   Distinct from edit-diffs (PostToolUse path): permission requests arrive
   pre-write so the renderer can render a proposal before approval."
  [permission-request]
  (let [content (get-in permission-request [:tool-call :content])]
    (when (seq content)
      (let [diffs (filterv #(= "diff" (:type %)) content)]
        (when (seq diffs) diffs)))))

(defn read-event?
  "True when a tool-call-update is a Read response event (any payload)."
  [{:keys [session-update] :as update}]
  (and (= :tool-call-update session-update)
       (= "Read" (get-in update [:meta :claude-code :tool-name]))))

(defn read-completed?
  "True when a Read tool-call-update carries the file metadata that lets the
   chat render a completed Read message."
  [update]
  (and (read-event? update)
       (some? (get-in update [:meta :claude-code :tool-response :file]))))

(defn edit-completed?
  "True when a tool-call-update is a PostToolUse event containing diff content.
   Excludes streaming refinement events which carry :kind.

   Gating note: matched by diff-content signature, not by tool-name. Empirically
   only the Edit/Write tools emit diff content in ACP, so the structural check
   stands in for a tool-name guard. This is intentionally asymmetric with
   read-completed?, which gates on tool-name = \"Read\"."
  [{:keys [session-update content kind]}]
  (and (= :tool-call-update session-update)
       (nil? kind)
       (some #(= "diff" (:type %)) content)))

(defn tool-call-update-status
  "Extracts the status string from a tool-call-update, or nil."
  [{:keys [session-update status]}]
  (when (= :tool-call-update session-update)
    status))

(defn config-update-mode
  "Extracts the session permission mode from a config-option-update, or nil.
   Reads the \"mode\" config option's :current-value (e.g. \"default\")."
  [{:keys [session-update config-options]}]
  (when (= :config-option-update session-update)
    (some #(when (= "mode" (:id %)) (:current-value %)) config-options)))

;; ACP Update sub-schemas
(def AcpTextContent
  "Schema for text content in ACP chunks. Consumer reads :text only."
  [:map
   [:text :string]])

(def AcpToolMetaClaudeCode
  "Schema for ACP metadata emitted by Claude Code tools."
  [:map
   [:tool-name {:optional true} :string]])

(def AcpToolMeta
  "Schema for ACP tool call metadata."
  [:map
   [:claude-code {:optional true} AcpToolMetaClaudeCode]])

(def AcpDiffItem
  "Structured diff content from a tool-call-update write operation.
   old-text is optional — absent when ACP creates a new file."
  [:map
   [:type [:= "diff"]]
   [:path :string]
   [:old-text {:optional true} :string]
   [:new-text :string]])

(def AcpToolCallContentItem
  "Tool call content blocks. Only :diff items have a load-bearing shape today;
   other :type values pass as opaque maps until a consumer needs them."
  [:multi {:dispatch (fn [m] (some-> (:type m) name keyword))}
   [:diff       AcpDiffItem]
   [::m/default :map]])

(def AcpToolCallStatus
  "ACP tool call lifecycle states.
   Optional on the wire: ToolCall omission defaults to \"pending\"; ToolCallUpdate
   omission is a delta meaning \"unchanged\". See agentclientprotocol.com/protocol/tool-calls."
  [:enum "pending" "in_progress" "completed" "failed"])

(def AcpToolKind
  "ACP tool operation kinds."
  [:enum "read" "edit" "delete" "move" "search" "execute" "think" "fetch" "switch_mode" "other"])

(def AcpAvailableCommandsUpdate
  "Dispatch-only — no field consumed by our renderer."
  [:map [:session-update [:= :available-commands-update]]])

(def AcpAgentThoughtChunk
  "Reasoning chunk; consumer reads :content.:text via acp-update-text."
  [:map
   [:session-update [:= :agent-thought-chunk]]
   [:content AcpTextContent]])

(def AcpAgentMessageChunk
  "Assistant chunk; consumer reads :content.:text via acp-update-text."
  [:map
   [:session-update [:= :agent-message-chunk]]
   [:content AcpTextContent]])

;; Upstream references: use the package version from package.json/lockfile.
;; - agentclientprotocol/typescript-sdk: schema/schema.json,
;;   src/schema/types.gen.ts, src/schema/zod.gen.ts
;; - agentclientprotocol/claude-agent-acp: src/acp-agent.ts, src/tools.ts
;; Search for: SessionUpdate, ToolCall, ToolCallUpdate,
;; RequestPermissionRequest, UsageUpdate, ToolKind, toolInfoFromToolUse,
;; toolUpdateFromDiffToolResponse, toAcpNotifications.

;; TODO: Design smell: :tool-call (pre-execution begin) vs :tool-call-update (streaming refinement/completion) share a name-head
;; and read as "a tool call" / "an update to it" — but the two events play distinct roles in per-tool flows (see web-search-started?, web-search-updated?, read-completed?).
;; Wire names mirror the upstream SDK, so we keep them; rename would be :tool-call-begin / :tool-call-progress if ever decoupled.
(def AcpToolCall
  "Pre-execution tool call notification.
   Side-channel: remember-tool-name reads :tool-call-id + :meta.:claude-code.:tool-name."
  [:map
   [:session-update [:= :tool-call]]
   [:tool-call-id :string]
   [:status    {:optional true} AcpToolCallStatus]
   [:raw-input {:optional true} [:map [:query {:optional true} :string]]]
   [:meta {:optional true} AcpToolMeta]])

;; TODO: DRY with AcpToolCall
(def AcpToolCallUpdate
  "Post-execution / streaming refinement update.
   Consumers: web-search-updated?, read-completed?, edit-completed?,
   edit-diffs, acp-read-display-label, read-event?.
   :kind absence (vs. presence) gates streaming-refinement filtering."
  [:map
   [:session-update [:= :tool-call-update]]
   [:tool-call-id :string]
   [:status    {:optional true} AcpToolCallStatus]
   [:raw-input {:optional true} [:map [:query {:optional true} :string]]]
   [:kind    {:optional true} [:maybe AcpToolKind]]
   [:meta    {:optional true} AcpToolMeta]
   [:content {:optional true} [:maybe [:vector AcpToolCallContentItem]]]])

(def AcpUsageUpdate
  "Dispatch-only — declared so SDK-emitted usage_update does not trigger
   schema-rejection log spam (see #244)."
  [:map [:session-update [:= :usage-update]]])

(def AcpConfigOptionUpdate
  "Emitted when a session config option changes (e.g. after setSessionMode).
   Consumer: config-update-mode reads the \"mode\" option's :current-value.
   :config-options entries are kept as open maps — only the mode value is load-bearing."
  [:map
   [:session-update  [:= :config-option-update]]
   [:config-options {:optional true} [:vector :map]]])

(def AcpUpdate
  "Discriminated union of ACP session update types. Dispatches on :session-update."
  [:multi {:dispatch (fn [m]
                       ;; Accept both normalized and raw bridge keys.
                       ;; Most paths normalize keys before coercion, but tests and
                       ;; internal callers may pass pre-coerced CLJS maps directly.
                       (some-> (or (:session-update m)
                                   (:sessionUpdate m))
                               csk/->kebab-case
                               keyword))}
   [:available-commands-update AcpAvailableCommandsUpdate]
   [:agent-thought-chunk        AcpAgentThoughtChunk]
   [:agent-message-chunk        AcpAgentMessageChunk]
   [:tool-call                  AcpToolCall]
   [:tool-call-update           AcpToolCallUpdate]
   [:usage-update               AcpUsageUpdate]
   [:config-option-update       AcpConfigOptionUpdate]])

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

(def AcpPermissionRawInput
  "Tool invocation arguments at a permission request.
   Open map: different tool kinds carry different keys.
   Edit/Write tools include :path or :file-path."
  [:map
   [:path      {:optional true} :string]
   [:file-path {:optional true} :string]])

(def AcpPermissionToolCall
  "Tool call context within an ACP permission request.
   RequestPermissionRequest.toolCall is a ToolCallUpdate (delta) per spec:
   only :tool-call-id is required on the wire. :tool-name is not an ACP field
   — it is enrichment-populated from prior tool_call session updates by
   acp.permission/enrich-permission-params (see that ns for the registry
   pattern and Zed references).
   Consumer: acp-permission/resolve-permission reads :kind, :tool-name (on
   \"fetch\"), and (on \"edit\") :raw-input.:path / :raw-input.:file-path.
   :content carries diff items on edit permission requests (Claude Agent SDK
   includes them so the client can render a proposal before approval)."
  [:map
   [:tool-call-id              :string]
   [:kind                      AcpToolKind]
   [:tool-name {:optional true} :string]
   [:raw-input {:optional true} AcpPermissionRawInput]
   [:content   {:optional true} [:maybe [:vector AcpToolCallContentItem]]]])

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
