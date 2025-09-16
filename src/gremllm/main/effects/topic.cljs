(ns gremllm.main.effects.topic
  "Topic persistence side effects and file I/O operations"
  (:require [gremllm.main.io :as io]
            [clojure.edn :as edn]
            [gremllm.schema :as schema]))

(defn save
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
            (when-let [topic (-> (io/read-file filepath)
                                 (parse-topic-content filepath))]
              (assoc topics (:id topic) topic)))
          {}
          (enumerate topics-dir)))
