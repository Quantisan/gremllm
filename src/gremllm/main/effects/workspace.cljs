(ns gremllm.main.effects.workspace
  "Topic persistence side effects and file I/O operations"

  ;; Runtime dependency: electron/main dialog
  ;; Loaded dynamically to support testing outside Electron environment
  (:require [gremllm.main.io :as io]
            [clojure.edn :as edn]
            [gremllm.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Private Helpers

(defn- topic-filename?
  "Check if a filename represents a topic file"
  [filename]
  (.endsWith filename ".edn"))

(defn- parse-topic-content
  "Parse and validate topic content from EDN string.
   Returns topic or nil on parse error."
  [content filename]
  (try
    (-> content
        edn/read-string
        schema/topic-from-disk)
    (catch :default e
      (js/console.error "Invalid topic file" filename (ex-message e))
      nil)))

(defn- read-topic-file-info
  "Read file metadata for a topic file. Returns map with filename,
   filepath, and timestamps."
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

(defn- get-dialog []
  (when (exists? js/require)
    (try
      (.-dialog (js/require "electron/main"))
      (catch :default _ nil))))

(defn pick-folder-dialog [{:keys [dispatch]} _]
  (when-let [dialog (get-dialog)]
    (-> (.showOpenDialog dialog
                         #js {:title "Open Workspace Folder"
                              :properties #js ["openDirectory"]
                              :buttonLabel "Open"})
        (.then (fn [^js result]
                 (when-not (.-canceled result)
                   (let [workspace-folder-path (first (.-filePaths result))]
                     (dispatch [[:workspace.actions/open-folder workspace-folder-path]]))))))))

;;; ---------------------------------------------------------------------------
;;; Topic Delete Operations

(defn- user-confirmed-deletion?
  "Check if user confirmed deletion in dialog result."
  [result]
  (= 1 (.-response result)))

(defn- attempt-delete
  "Delete topic file, logging errors on failure."
  [filepath]
  (try
    (io/delete-file filepath)
    (catch :default e
      (js/console.error "Failed to delete topic file" filepath (ex-message e)))))

(defn delete-topic-with-confirmation
  "Execute topic deletion plan: optionally confirm, then delete from disk."
  [{:keys [filepath confirmation-message confirmation-detail]}]
  (if-let [dialog (get-dialog)]
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

;;; ---------------------------------------------------------------------------
;;; Workspace Sync Operations

(defn load-and-sync
  "Load topics and workspace metadata, then send sync payload to renderer."
  [{:keys [dispatch]} _ workspace-path]
  (let [workspace-name (io/path-basename workspace-path)
        workspace-meta (schema/create-workspace-meta workspace-name)
        topics         (-> workspace-path io/topics-dir-path load-topics)
        sync-payload   (schema/workspace-sync-for-ipc topics workspace-meta)]
    (dispatch [[:ipc.effects/send-to-renderer "workspace:opened" sync-payload]])))
