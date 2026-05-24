(ns gremllm.schema.codec
  (:require [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ========================================
;; Disk Codecs
;; ========================================

(def topic-from-disk
  "Loads and validates a topic from persisted EDN format. Applies defaults for
   fields added after initial save. Throws if the topic data is invalid."
  (m/coercer schema/Topic
             (mt/transformer mt/default-value-transformer
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

(def DocumentSyncData
  "Schema for document data sent from main to renderer via IPC.
   Used when loading a document from disk."
  [:map
   [:document-meta [:map [:name :string]]]
   [:topics {:default {}} schema/DocumentTopics]
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

(defn document-sync-from-ipc
  "Validates and transforms document sync data from IPC. Throws if invalid."
  [sync-data-js]
  (as-> sync-data-js $
    (js->clj $ :keywordize-keys true)
    (m/coerce DocumentSyncData $ mt/json-transformer)))

(defn document-sync-for-ipc
  "Validates and prepares document sync data for IPC transmission. Throws if invalid."
  [topics document-meta document]
  (m/coerce DocumentSyncData
            {:topics topics :document-meta document-meta :document document}
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
