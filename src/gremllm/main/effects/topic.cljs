(ns gremllm.main.effects.topic
  "Topic persistence side effects and file I/O operations."
  (:require [gremllm.main.electron :as electron]
            [gremllm.main.io :as io]
            [clojure.edn :as edn]
            [gremllm.schema.codec :as codec]))

;;; ---------------------------------------------------------------------------
;;; Private Helpers

(defn- topic-filename?
  [filename]
  (.endsWith filename ".edn"))

(defn- parse-topic-content
  "Parse and validate topic content from EDN string.
   Returns topic or nil on parse error."
  [content filename]
  (try
    (-> content
        edn/read-string
        codec/topic-from-disk)
    (catch :default e
      (js/console.error "Invalid topic file" filename (ex-message e))
      nil)))

(defn- read-topic-file-info
  [topics-dir filename]
  (let [filepath (io/path-join topics-dir filename)]
    (merge {:filename filename
            :filepath filepath}
           (io/file-timestamps filepath))))

;;; ---------------------------------------------------------------------------
;;; Topic File Operations

(defn save-topic
  "Performs file I/O to save a topic"
  [{:keys [dir filepath content]}]
  (io/ensure-dir dir)
  (io/write-file filepath content)
  filepath)

;;; ---------------------------------------------------------------------------
;;; Topic Collection Operations

(defn enumerate
  "List all topic files in directory with metadata.
   Returns vector of {:filename :filepath :created-at :last-accessed-at}
   sorted by filename. Returns [] if directory doesn't exist."
  [topics-dir]
  (if-not (io/file-exists? topics-dir)
    []
    (->> (io/read-dir topics-dir)
         (filter topic-filename?)
         (map (partial read-topic-file-info topics-dir))
         (sort-by :filename)
         vec)))

(defn load-topics
  "Load all topics from directory into map of {topic-id => topic}.
   Skips corrupt/invalid files with error logging.
   Returns empty map if directory doesn't exist."
  [topics-dir]
  (reduce (fn [topics {:keys [filepath]}]
            (if-let [topic (-> (io/read-file filepath)
                               (parse-topic-content filepath))]
              (assoc topics (:id topic) topic)
              topics))
          {}
          (enumerate topics-dir)))

;;; ---------------------------------------------------------------------------
;;; Dialog Operations

(defn- user-confirmed-deletion?
  [result]
  (= 1 (.-response result)))

(defn- attempt-delete
  [filepath]
  (try
    (io/delete-file filepath)
    (catch :default e
      (js/console.error "Failed to delete topic file" filepath (ex-message e)))))

(defn delete-topic-with-confirmation
  "Execute topic deletion plan: optionally confirm, then delete from disk."
  [{:keys [filepath confirmation-message confirmation-detail]}]
  (if-let [dialog (electron/get-dialog)]
    (-> (.showMessageBox dialog
                         #js {:type "warning"
                              :message confirmation-message
                              :detail confirmation-detail
                              :buttons #js ["Cancel" "Delete"]
                              :defaultId 0
                              :cancelId 0})
        (.then (fn [result]
                (when (user-confirmed-deletion? result)
                  (attempt-delete filepath)))))
    (js/Promise.resolve nil)))
