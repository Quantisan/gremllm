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

(defn generate-message-id
  "Generates numeric message IDs for chat messages."
  []
  (js/Date.now))

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
   [:messages {:default []}             [:vector Message]]])

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

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format. Applies defaults for fields added after
  initial save. Throws if the topic data is invalid."
  (m/coercer Topic (mt/transformer mt/default-value-transformer mt/json-transformer)))

(def topic-for-disk
  "Prepares topic for disk persistence, stripping transient fields.
  Applies defaults for any fields missing from in-memory topic. Throws if invalid."
  (m/coercer PersistedTopic (mt/transformer mt/default-value-transformer mt/strip-extra-keys-transformer)))

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
