(ns gremllm.main.io
  (:require ["path" :as path]
            ["fs" :as fs]))

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
