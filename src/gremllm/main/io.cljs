(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]
            [cljs.reader :as edn]))

(def ^:private user-subdir "User")
(def ^:private workspaces-subdir "workspaces")
(def ^:private default-workspace "default")

;; Clojure-friendly wrappers around Node's `path` API
(defn path-join
  "Join path segments using Node's path.join."
  [& segments]
  (.apply (.-join path) path (to-array segments)))

(defn path-dirname
  "Return the directory name of a path using Node's path.dirname."
  [p]
  (.dirname path p))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn user-dir-path
  "Build a path under the app's user scope directory (User)."
  [user-data-dir & segments]
  (apply path-join user-data-dir user-subdir segments))

(defn workspace-dir-path
  "Path to a specific workspace: <userData>/User/workspaces/<workspace-id>"
  [user-data-dir workspace-id]
  (user-dir-path user-data-dir workspaces-subdir workspace-id))

(defn ensure-dir [dir]
  (.mkdirSync fs dir #js {:recursive true}))

(defn write-file [filepath content]
  (.writeFileSync fs filepath content "utf8"))

(defn read-file [filepath]
  (.readFileSync fs filepath "utf8"))

(defn file-exists?
  "Return true if a file or directory exists at path."
  [p]
  (.existsSync fs p))

(defn read-dir
  "Return a seq of entries in dir."
  [dir]
  (array-seq (.readdirSync fs dir)))

(defn find-latest-topic-file [dir file-pattern]
  (when (file-exists? dir)
    (when-let [latest-file (->> (read-dir dir)
                                (filter #(re-matches file-pattern %))
                                sort
                                last)]
      (path-join dir latest-file))))

(defn secrets-file-path [user-data-dir]
  (user-dir-path user-data-dir "secrets.edn"))

(defn read-secrets-file [filepath]
  (if (file-exists? filepath)
    (try
      (edn/read-string (read-file filepath))
      (catch :default _
        {}))
    {}))

(defn write-secrets-file [filepath secrets-map]
  (ensure-dir (path-dirname filepath))
  (write-file filepath (pr-str secrets-map)))

(defn topics-dir-path
  "Path to topics within a workspace. Default workspace is \"default\".
   1-arity: <userData>/User/workspaces/default/topics
   2-arity: <userData>/User/workspaces/<workspace-id>/topics"
  ([user-data-dir]
   (topics-dir-path user-data-dir default-workspace))
  ([user-data-dir workspace-id]
   (path-join (workspace-dir-path user-data-dir workspace-id) "topics")))
