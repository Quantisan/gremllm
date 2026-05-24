(ns gremllm.main.effects.document
  "Document lifecycle I/O: open, load, sync to renderer."
  (:require [gremllm.main.io :as io]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]))

;;; ---------------------------------------------------------------------------
;;; Per-Document Metadata

(def ^:private meta-filename "meta.edn")

(defn write-meta-if-missing!
  "Persist {:doc-path ...} to <document-data-dir>/meta.edn, only if not already
   present. Not load-bearing for v1 — supports a future recent-documents UI."
  [document-data-dir doc-path]
  (let [meta-path (io/path-join document-data-dir meta-filename)]
    (when-not (io/file-exists? meta-path)
      (io/ensure-dir document-data-dir)
      (io/write-file meta-path (pr-str {:doc-path doc-path})))))

;;; ---------------------------------------------------------------------------
;;; Dialog Operations

(defn- get-dialog []
  (when (exists? js/require)
    (try
      (.-dialog (js/require "electron/main"))
      (catch :default _ nil))))

(defn pick-dialog [{:keys [dispatch]} _]
  (when-let [dialog (get-dialog)]
    (-> (.showOpenDialog dialog
                         #js {:title "Open"
                              :properties #js ["openFile"]
                              :filters #js [#js {:name "Markdown"
                                                 :extensions #js ["md" "markdown"]}]
                              :buttonLabel "Open"})
        (.then (fn [^js result]
                 (when-not (.-canceled result)
                   (let [doc-path (first (.-filePaths result))]
                     (dispatch [[:document.actions/open doc-path]]))))))))

;;; ---------------------------------------------------------------------------
;;; Document Sync Operations

(defn- read-document [doc-path]
  {:content (when (io/file-exists? doc-path)
              (io/read-file doc-path))})

(defn record-source-path
  "Nexus effect: record the document's filesystem location in the data dir."
  [_ctx _store {:keys [data-dir doc-path]}]
  (write-meta-if-missing! data-dir doc-path))

(defn load-and-sync
  "Read the document, load its topics from per-document storage,
   then send the sync payload to the renderer."
  [{:keys [dispatch]} _store {:keys [doc-path data-dir topics-dir]}]
  (let [document-name (io/path-basename doc-path)
        document-meta (schema/create-document-meta document-name)
        document      (read-document doc-path)
        topics        (topic-effects/load-topics topics-dir)
        sync-payload  (codec/document-sync-for-ipc topics document-meta document)]
    (dispatch [[:ipc.effects/send-to-renderer "document:opened" sync-payload]])))
