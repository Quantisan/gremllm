(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]))

(def topic-file-pattern #"topic-\d+\.edn")

(defn generate-filename [timestamp]
  (str "topic-" timestamp ".edn"))

(defn valid-filename? [filename]
  (re-matches topic-file-pattern filename))

(defn topic->save-plan
  [topic-clj {:keys [timestamp topics-dir]}]
  (let [filename (generate-filename timestamp)
        filepath (.join path topics-dir filename)]
    {:dir topics-dir
     :filename filename
     :filepath filepath
     :content (pr-str topic-clj)
     :topic topic-clj}))

(defn validate-save-plan
  [{:keys [filename] :as plan}]
  (when-not (valid-filename? filename)
    (throw (js/Error. (str "Invalid filename: " filename))))
  plan)

;; Pure functions that return data for effects
(defn prepare-save [topic-clj topics-dir]
  (-> (topic->save-plan topic-clj
                        {:timestamp (.getTime (js/Date.))
                         :topics-dir topics-dir})
      (validate-save-plan)))


