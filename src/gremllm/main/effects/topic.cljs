(ns gremllm.main.effects.topic
  "Topic persistence side effects and file I/O operations"
  (:require [gremllm.main.io :as io]
            [clojure.edn :as edn]))

(defn save
  "Performs file I/O to save a topic"
  [{:keys [dir filepath content]}]
  (io/ensure-dir dir)
  (io/write-file filepath content)
  filepath)

(defn load
  "Loads the latest topic from the file system"
  [topics-dir topic-file-pattern]
  (when-let [filepath (io/find-latest-topic-file topics-dir topic-file-pattern)]
    (-> (io/read-file filepath)
        edn/read-string
        (clj->js :keywordize-keys false))))
