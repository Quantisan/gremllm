(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]))

(def topic-file-pattern #"topic-\d+-\w+\.edn")

(defn generate-filename [topic-id]
  (str topic-id ".edn"))

(defn valid-filename? [filename]
  (re-matches topic-file-pattern filename))

(defn topic->save-plan
  [topic-clj {:keys [topics-dir]}]
  (if-let [id (:id topic-clj)]
    (let [filename (generate-filename id)
          filepath (path/join topics-dir filename)]
      {:dir topics-dir
       :filename filename
       :filepath filepath
       :content (pr-str topic-clj)
       :topic topic-clj})
    (throw (js/Error. "Topic must have an :id field"))))

(defn validate-save-plan
  [{:keys [filename] :as plan}]
  (when-not (valid-filename? filename)
    (throw (js/Error. (str "Invalid filename: " filename))))
  plan)

;; Pure functions that return data for effects
(defn prepare-save [topic-clj topics-dir]
  (-> (topic->save-plan topic-clj
                        {:topics-dir topics-dir})
      (validate-save-plan)))


