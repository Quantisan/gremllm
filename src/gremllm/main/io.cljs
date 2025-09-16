(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]
            [cljs.reader :as edn]))

(def ^:private user-subdir "User")
(def ^:private topics-subdir "topics")

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

(defn- file-stats
  "Return Node.js fs.Stats for the given filepath."
  [filepath]
  (.statSync fs filepath))

(defn file-timestamps
  "Return a map of file timestamp metadata (in ms since epoch)."
  [filepath]
  (let [stats (file-stats filepath)
        created-ms  (or (.-birthtimeMs stats)
                        (.-ctimeMs stats)
                        (some-> (.-birthtime stats) (.getTime))
                        (some-> (.-ctime stats) (.getTime)))
        accessed-ms (or (.-atimeMs stats)
                        (some-> (.-atime stats) (.getTime)))]
    {:created-at       created-ms
     :last-accessed-at accessed-ms}))

(defn read-dir
  "Return a seq of entries in dir."
  [dir]
  (array-seq (.readdirSync fs dir)))


(defn delete-file
  "Delete a file at filepath."
  [filepath]
  (.unlinkSync fs filepath))

(defn remove-dir
  "Remove an empty directory."
  [dir]
  (.rmdirSync fs dir))

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

(defn topics-dir-path [workspace-dir]
  (path-join workspace-dir topics-subdir))

