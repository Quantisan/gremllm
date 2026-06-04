(ns gremllm.schema
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]
            [gremllm.schema.codec.acp :as acp-codec]))

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

(defn generate-message-id
  "Generates numeric message IDs for chat messages."
  []
  (js/Date.now))

;; ========================================
;; Excerpt (Selection Capture)
;; ========================================

(def ViewportRect
  "Viewport-relative rectangle from getBoundingClientRect / getClientRects."
  [:map
   [:top number?] [:left number?] [:width number?] [:height number?]])

(def SelectionGeometry
  "Positioning data for popover placement (S7.2 consumer).
   bounding-rect spans the full selection; client-rects are per-line/per-span."
  [:map
   [:bounding-rect ViewportRect]
   [:client-rects [:vector ViewportRect]]])

(def SelectionContent
  "Range content and offset data for staging (S7.3) and source mapping (S7.5).
   Container node names and text content from the browser Range API."
  [:map
   [:start-container :string]
   [:start-text :string]
   [:start-offset :int]
   [:end-container :string]
   [:end-text :string]
   [:end-offset :int]
   [:common-ancestor :string]])

(def SelectionRange
  "Combined range data from browser Range API. Composes content + geometry."
  (mu/merge SelectionContent SelectionGeometry))

(def CapturedSelection
  "Data captured from browser Selection API on mouseup.
   Stored at [:excerpt :captured] after coercion through captured-selection-from-dom."
  [:map
   [:text :string]
   [:range-count :int]
   [:anchor-node :string]
   [:anchor-offset :int]
   [:focus-node :string]
   [:focus-offset :int]
   [:range SelectionRange]])

(def AnchorContext
  "Panel geometry snapshot captured at selection time.
   Ephemeral — only valid until scroll or resize dismisses the popover.
   Stored at [:excerpt :anchor]."
  [:map
   [:panel-rect ViewportRect]
   [:panel-scroll-top number?]])

(def BlockRef
  "Rendered-block identity captured at selection time.
   Advisory only — not exact markdown-source anchoring."
  [:map
   [:kind :keyword]
   [:index :int]
   [:start-line :int]
   [:end-line :int]
   [:block-text-snippet :string]])

(def DocumentExcerpt
  "Durable user-curated document reference. Paired with a user message
   as AI context. Locator is advisory rendered-block metadata."
  [:map
   [:id :string]
   [:text :string]
   [:locator
    [:map
     [:document-relative-path :string]
     [:start-block BlockRef]
     [:end-block BlockRef]]]])

;; Per-variant Message schemas. Each variant fixes its field/type affinity at
;; the schema level so validators catch mismatches that the old loose Message
;; accepted silently.

(def UserMessage
  [:map {:closed true}
   [:id   :int]
   [:type [:enum :user]]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]
   [:context     {:optional true}
    [:map [:excerpts [:vector DocumentExcerpt]]]]])

(def AssistantMessage
  [:map {:closed true}
   [:id   :int]
   [:type [:enum :assistant]]
   [:text :string]])

(def ReasoningMessage
  [:map {:closed true}
   [:id   :int]
   [:type [:enum :reasoning]]
   [:text :string]])

(def ToolName
  "Tools displayed in chat. Add a value when a new tool earns a renderer."
  [:enum :web-search :read])

(def ToolCallMessage
  "Tool-call message kind. :tool-call-status carries the lifecycle; one-shot
   mints use \"completed\". Per-tool extras (e.g. :query for :web-search) are
   flat optional fields for now; promote to a sub-:multi on :tool once a
   second tool needs extras."
  [:map {:closed true}
   [:id               :int]
   [:type             [:enum :tool-call]]
   [:tool-call-id     :string]
   [:tool             ToolName]
   [:tool-call-status acp-codec/AcpToolCallStatus]
   [:text             :string]
   [:query            {:optional true} [:maybe :string]]]) ; :web-search

(def Message
  "Tagged union of chat message kinds. Dispatch on :type."
  [:multi {:dispatch (fn [v] (-> v :type keyword))}
   [:user      UserMessage]
   [:assistant AssistantMessage]
   [:reasoning ReasoningMessage]
   [:tool-call ToolCallMessage]])

(def Messages
  [:vector Message])

;; ========================================
;; Topics & Workspaces
;; ========================================

(defn generate-topic-id []
  ;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
  ;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

(def PendingDiff
  [:map
   [:type [:= "diff"]]
   [:path :string]
   [:old-text {:optional true} :string]
   [:new-text :string]])

(def AcpSession
  "Session state produced by an ACP agent for a topic."
  ;; TODO: runtime [:session] also carries two transient keys not declared here:
  ;;   :resolved-tool-calls         #{tool-call-id ...}
  ;;   :pending-permission-options  {tool-call-id [AcpPermissionOption ...]}
  ;; Written by renderer.actions.topic/append-pending-permission and
  ;; resolve-diff-actions. Omitted because mt/strip-extra-keys-transformer in
  ;; codec/topic-for-disk doubles as the disk allowlist — declaring them here
  ;; would start persisting them. Fix is a split: PersistedAcpSession (current
  ;; shape, disk) + in-memory AcpSession that mu/merges the transients,
  ;; mirroring PersistedTopic/Topic.
  [:map
   ;; TODO: id should be required
   [:id {:optional true}         :string]
   [:pending-diffs {:default []} [:vector PendingDiff]]])

(def PersistedTopic
  "Schema for topics as saved to disk"
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"}        :string]
   [:session {:default {}}              AcpSession]
   [:messages {:default []}             [:vector Message]]
   [:excerpts {:default []}             [:vector DocumentExcerpt]]])

;; TODO: Pivot domain model -- Topic should be Session.
(def Topic
  "Schema for topics in app state (includes transient fields)"
  (mu/merge
    PersistedTopic
    [:map
     ;; TODO(slice2): persist anchor in PersistedTopic. Required-vs-optional there
     ;; is a product call, not a schema call: it hinges on whether unanchored
     ;; whole-doc sessions (see start-new-topic) survive the Topic->Session pivot.
     [:anchor {:optional true} DocumentExcerpt]

     [:unsaved? {:optional true} :boolean]]))

;; TODO: refactor with (generate-topic-id)
(def TopicId
  "Schema for topic identifiers shared across IPC boundaries."
  [:string {:min 1}])

(defn create-topic []
  (m/decode Topic {} mt/default-value-transformer))

(def DocumentTopics
  "Map of Topics keyed by Topic ID"
  [:map-of :string Topic])

(defn valid-document-topics? [topics-map]
  (m/validate DocumentTopics topics-map))

(def DocumentMeta
  "Metadata about a document, sent over IPC during sync."
  [:map [:name :string]])

(defn create-document-meta
  "Constructor for document metadata sent over IPC."
  [name]
  (m/decode DocumentMeta {:name name} mt/default-value-transformer))
