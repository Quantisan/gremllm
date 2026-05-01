(ns gremllm.schema
  (:require [malli.core :as m]
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

(def Message
  [:map
   [:id :int]
   [:type MessageType]
   [:text :string]
   [:attachments {:optional true} [:vector AttachmentRef]]
   [:context {:optional true}
    [:map
     [:excerpts [:vector DocumentExcerpt]]]]])

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
