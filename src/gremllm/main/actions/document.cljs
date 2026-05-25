(ns gremllm.main.actions.document
  (:require [gremllm.main.io :as io]
            [gremllm.main.state :as state]))

(defn open [state doc-path]
  (let [paths (io/document-paths (state/get-user-data-dir state) doc-path)]
    [[:store.effects/save state/active-document-path doc-path]
     [:document.effects/load-and-sync paths]
     [:document.effects/record-source-path paths]]))

(defn reload [state]
  (let [paths (state/get-document-paths state)]
    [[:document.effects/load-and-sync paths]]))
