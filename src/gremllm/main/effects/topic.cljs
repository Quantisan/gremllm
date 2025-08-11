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

(defn- file->topic-entry [topics-dir pattern filename]
  (when (re-matches pattern filename)
    (let [filepath (io/path-join topics-dir filename)]
      (merge {:filename filename
              :filepath filepath}
             (io/file-timestamps filepath)))))

(defn enumerate
  "Enumerate persisted topics for a workspace. Reads topics-dir and returns a vector of
  maps {:filename string :filepath string} for files whose names match topic-file-pattern
  (e.g., #\"topic-\\d+\\.edn\"). The result is sorted by filename ascending. Returns []
  if topics-dir does not exist; does not read or parse file contents."
  [topics-dir topic-file-pattern]
  (if-not (io/file-exists? topics-dir)
    []
    (->> (io/read-dir topics-dir)
         (keep (partial file->topic-entry topics-dir topic-file-pattern))
         (sort-by :filename)
         vec)))

(defn load-latest
  "Loads the latest topic from the file system (reuses enumerate for DRY)."
  [topics-dir topic-file-pattern]
  ;; TODO: output of `enumerate` isn't sorted by last-accesssed, thus last is not latest
  (when-let [{:keys [filepath]} (last (enumerate topics-dir topic-file-pattern))]
    (-> (io/read-file filepath)
        edn/read-string
        (clj->js :keywordize-keys false))))
