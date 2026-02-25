(ns gremllm.renderer.ui.markdown
  (:require [nextjournal.markdown :as md]))

(def renderers
  (assoc md/default-hiccup-renderers
    :doc (fn [ctx node]
           (md/into-hiccup [:div] ctx node))))

(defn markdown->hiccup
  "Converts a markdown string to Replicant hiccup."
  [text]
  (when (seq text)
    (md/->hiccup renderers text)))
