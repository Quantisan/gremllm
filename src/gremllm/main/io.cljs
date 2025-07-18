(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]
            [cljs.reader :as edn]))

(defn find-latest-topic-file [dir file-pattern]
  (when (.existsSync fs dir)
    (when-let [latest-file (->> (.readdirSync fs dir)
                                (filter #(re-matches file-pattern %))
                                (sort)
                                (last))]
      (.join path dir latest-file))))

(defn ensure-dir [dir]
  (.mkdirSync fs dir #js {:recursive true}))

(defn write-file [filepath content]
  (.writeFileSync fs filepath content "utf8"))

(defn read-file [filepath]
  (.readFileSync fs filepath "utf8"))

(defn secrets-file-path [user-data-dir]
  (.join path user-data-dir "User" "secrets.edn"))

(defn read-secrets-file [filepath]
  (if (.existsSync fs filepath)
    (try
      (edn/read-string (read-file filepath))
      (catch :default _
        {}))
    {}))

(defn write-secrets-file [filepath secrets-map]
  (let [dir-path (.dirname path filepath)]
    (ensure-dir dir-path)
    (write-file filepath (pr-str secrets-map))))
