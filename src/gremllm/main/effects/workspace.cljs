(ns gremllm.main.effects.workspace
  "Topic persistence side effects and file I/O operations"

  ;; Runtime dependency: electron/main dialog
  ;; Loaded dynamically to support testing outside Electron environment
  (:require [gremllm.main.io :as io]
            [clojure.edn :as edn]
            [gremllm.schema :as schema]))

(defn save-topic
  "Performs file I/O to save a topic"
  [{:keys [dir filepath content]}]
  (io/ensure-dir dir)
  (io/write-file filepath content)
  filepath)

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

(defn load-all
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

(defn load-and-sync [{:keys [dispatch]} _ workspace-folder-path]
  (let [topics-dir (io/topics-dir-path workspace-folder-path)
        topics (load-all topics-dir)
        workspace-data (schema/workspace-sync-for-ipc topics)]
    (dispatch [[:ipc.effects/send-to-renderer "workspace:sync" workspace-data]])))
