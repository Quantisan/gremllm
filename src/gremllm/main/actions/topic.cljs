(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]
            [gremllm.schema :as schema]))

(defn generate-filename [topic-id]
  (str topic-id ".edn"))

(defn topic->save-plan
  [topic-clj topics-dir]
  (let [persisted-topic (schema/encode-persisted-topic topic-clj)
        filename (generate-filename (:id persisted-topic))
        filepath (path/join topics-dir filename)]
    {:dir topics-dir
     :filename filename
     :filepath filepath
     :content (pr-str persisted-topic)
     :topic persisted-topic}))

