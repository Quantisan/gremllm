(ns gremllm.main.actions.topic
  "Pure actions for topic operations"
  (:require ["path" :as path]
            [malli.core :as m]
            [malli.transform :as mt]
            [gremllm.schema :as schema]))

(defn generate-filename [topic-id]
  (str topic-id ".edn"))

(def strip-optional-transformer
  "Transformer that removes optional map entries"
  (mt/transformer
    {:name :strip-optional
     :decoders {:map (fn [schema]
                       (let [entries (m/entries schema)
                             optional-keys (->> entries
                                                (filter (fn [[k _ props]]
                                                          (:optional props)))
                                                (map first)
                                                set)]
                         (fn [x]
                           (apply dissoc x optional-keys))))}}))

(defn topic->save-plan
  [topic-clj topics-dir]
  (let [stripped-topic (m/encode schema/Topic topic-clj strip-optional-transformer)
        filename (generate-filename (:id stripped-topic))
        filepath (path/join topics-dir filename)]
    {:dir topics-dir
     :filename filename
     :filepath filepath
     :content (pr-str stripped-topic)
     :topic stripped-topic}))

