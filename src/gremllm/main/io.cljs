(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]
            [cljs.reader :as edn]))

;; Clojure-friendly wrappers around Node's `path` API
(defn path-join
  "Join path segments using Node's path.join."
  [& segments]
  (.apply (.-join path) path (to-array segments)))

(defn path-dirname
  "Return the directory name of a path using Node's path.dirname."
  [p]
  (.dirname path p))

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
  (path-join user-data-dir "User" "secrets.edn"))

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

(defn topics-dir-path [user-data-dir]
  (path-join user-data-dir "User" "topics"))
