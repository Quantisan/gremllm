(ns gremllm.main.actions.topic
  (:require [gremllm.main.io :as io]
            [clojure.edn :as edn]
            ["path" :as path]))

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

(defn execute-save-plan!
  [{:keys [dir filepath content]}]
  (io/ensure-dir dir)
  (io/write-file filepath content)
  filepath)

(defn save [_ _ topic-clj topics-dir]
  (-> (topic->save-plan topic-clj
                        {:timestamp (.getTime (js/Date.))
                         :topics-dir topics-dir})
      (validate-save-plan)
      (execute-save-plan!)))

(defn load [_ _ topics-dir]
    (when-let [filepath (io/find-latest-topic-file topics-dir topic-file-pattern)]
      (-> (io/read-file filepath)
          edn/read-string
          (clj->js :keywordize-keys false))))


