(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]
            ["crypto" :as crypto]
            ["node:url" :as node-url]))

(def ^:private user-subdir "User")
(def ^:private documents-subdir "documents")
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

(defn path-basename
  "Return the basename (final path segment) of a path using Node's path.basename."
  [p]
  (.basename path p))

(defn path->file-uri
  "Convert a filesystem path to a properly encoded file:// URI."
  [p]
  (-> (node-url/pathToFileURL p)
      (.toString)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn ensure-dir [dir]
  (.mkdirSync fs dir #js {:recursive true}))

(defn write-file [filepath content]
  (.writeFileSync fs filepath content "utf8"))

(defn read-file [filepath]
  (.readFileSync fs filepath "utf8"))

(defn delete-file [filepath]
  (.unlinkSync fs filepath))

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

(defn path->document-hash
  "SHA-256 (hex) of the document's normalized absolute path. Stable key for
   locating a document's per-document state under userData."
  [doc-path]
  (-> (crypto/createHash "sha256")
      (.update (path/resolve doc-path))
      (.digest "hex")))

(defn document-paths
  "Build all per-document paths from root inputs. Returns a plain map —
   the single source of truth for the document path hierarchy."
  [user-data-dir doc-path]
  (let [hash     (path->document-hash doc-path)
        data-dir (path-join user-data-dir user-subdir documents-subdir hash)]
    {:doc-path   doc-path
     :data-dir   data-dir
     :topics-dir (path-join data-dir topics-subdir)}))
