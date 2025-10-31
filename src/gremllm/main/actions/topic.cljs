(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]
            [gremllm.schema :as schema]))

(defn topic-filename
  "Generate filename for a topic (e.g., 'abc123.edn')"
  [topic-id]
  (str topic-id ".edn"))

(defn topic-filepath
  "Build full filepath for a topic in given directory"
  [dir topic-id]
  (path/join dir (topic-filename topic-id)))

(defn topic->save-plan
  "Create save plan for persisting a topic"
  [topic-clj topics-dir]
  (let [persisted-topic (schema/topic-for-disk topic-clj)]
    {:dir      topics-dir
     :filepath (topic-filepath topics-dir (:id persisted-topic))
     :content  (pr-str persisted-topic)}))

(defn topic->delete-plan
  "Create delete plan for removing a topic from disk."
  [topic-id topics-dir]
  {:topic-id               topic-id
   :filepath               (topic-filepath topics-dir topic-id)
   :confirmation-message   "Delete topic?" ;; TODO: include topic-name
   :confirmation-detail    "This action cannot be undone."})
