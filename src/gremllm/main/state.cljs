(ns gremllm.main.state
  "State paths and accessors for the main process store"
  (:require [gremllm.main.io :as io]))

;; Path constants
(def active-document-path [:active-document-path])
(def user-data-dir [:user-data-dir])

;; State accessor functions
(defn get-active-document-path [state]
  (get-in state active-document-path))

(defn get-user-data-dir [state]
  (get-in state user-data-dir))

(defn get-storage-dir
  "Per-document state directory for the active document, or nil if no document
   is open (or userData is not yet known)."
  [state]
  (let [doc-path      (get-active-document-path state)
        user-data-dir (get-user-data-dir state)]
    (when (and doc-path user-data-dir)
      (io/document-storage-dir user-data-dir doc-path))))

(defn get-topics-dir
  "Topics directory under the active document's storage dir, or nil."
  [state]
  (some-> (get-storage-dir state) io/topics-dir-path))
