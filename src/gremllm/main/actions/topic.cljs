(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]))

(defn generate-filename [topic-id]
  (str topic-id ".edn"))

(defn topic->save-plan
  [topic-clj topics-dir]
  (if-let [id (:id topic-clj)]
    (let [filename (generate-filename id)
          filepath (path/join topics-dir filename)]
      {:dir topics-dir
       :filename filename
       :filepath filepath
       :content (pr-str topic-clj)
       :topic topic-clj})
    (throw (js/Error. "Topic must have an :id field"))))

