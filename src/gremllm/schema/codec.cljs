(ns gremllm.schema.codec
  (:require [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ========================================
;; Disk Codecs
;; ========================================

(defn- migrate-legacy-message
  "Rewrites a single legacy message map to the unified :tool-call shape.
   - :tool-search → :tool-call with :tool :web-search
   - :tool-use    → :tool-call with :tool :read, synthetic :tool-call-id,
                    and :tool-call-status \"completed\""
  [m]
  (case (:type m)
    :tool-search
    (-> m
        (assoc :type :tool-call
               :tool :web-search)
        (update :tool-call-status #(or % "completed")))

    :tool-use
    (-> m
        (assoc :type :tool-call
               :tool :read
               :tool-call-status "completed")
        (update :tool-call-id #(or % (str "legacy-" (:id m)))))

    m))

(def ^:private legacy-message-transformer
  "Migrates retired :tool-search/:tool-use message variants on topic load.
   Runs at the Topic-level :map decoder (enter phase) so the rewrite happens
   before [:multi] dispatch sees the messages. Remove once persisted topics
   from versions < <next-release> have aged out."
  (mt/transformer
    {:decoders
     {:map (fn [m]
             (cond-> m
               (seq (:messages m)) (update :messages #(mapv migrate-legacy-message %))))}}))

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format. Applies defaults for
   fields added after initial save. Normalizes legacy message shapes. Throws
   if the topic data is invalid."
  (m/coercer schema/Topic
             (mt/transformer mt/default-value-transformer
                             legacy-message-transformer
                             mt/json-transformer)))

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
