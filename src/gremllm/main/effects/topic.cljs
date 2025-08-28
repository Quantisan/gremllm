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

(defn- file->topic-entry [topics-dir filename]
  (when (.endsWith filename ".edn")
    (let [filepath (io/path-join topics-dir filename)]
      (merge {:filename filename
              :filepath filepath}
             (io/file-timestamps filepath)))))

(defn enumerate
  "Enumerate all .edn files in topics-dir. Returns a vector of maps 
  {:filename string :filepath string :created-at :last-accessed-at} 
  sorted by filename. Returns [] if topics-dir does not exist."
  [topics-dir]
  (if-not (io/file-exists? topics-dir)
    []
    (->> (io/read-dir topics-dir)
         (keep (partial file->topic-entry topics-dir))
         (sort-by :filename)
         vec)))

(defn load-latest
  "Loads the latest topic from the file system."
  [topics-dir]
  ;; TODO: output of `enumerate` isn't sorted by last-accesssed, thus last is not latest
  (when-let [{:keys [filepath]} (last (enumerate topics-dir))]
    (-> (io/read-file filepath)
        edn/read-string
        (clj->js :keywordize-keys false))))

(defn load-all
  "Load all topics from the filesystem into a map of {<topic-id> Topic}.
  Returns an empty map if topics-dir doesn't exist or contains no topics."
  [topics-dir]
  (reduce (fn [acc {:keys [filename filepath]}]
            (try
              (let [topic-content (-> (io/read-file filepath)
                                      edn/read-string)]
                (if-let [topic-id (:id topic-content)]
                  (assoc acc topic-id topic-content)
                  (do
                    (js/console.warn "Topic file missing :id field" filename)
                    acc)))
              (catch :default e
                ;; Log error but continue loading other topics
                (js/console.error "Failed to load topic file" filename e)
                acc)))
          {}
          (enumerate topics-dir)))
