(ns gremllm.main.actions.document
  (:require [gremllm.main.io :as io]
            [gremllm.main.state :as state]))

(defn open [state doc-path]
  (let [data-dir (io/document-data-dir (state/get-user-data-dir state) doc-path)]
    [[:store.effects/save state/active-document-path doc-path] ;; refactor this out as a domain action if this gets used elsewhere
     [:document.effects/load-and-sync doc-path data-dir]]))

(defn reload [state]
  (let [doc-path (state/get-active-document-path state)
        data-dir (state/get-document-data-dir state)]
    [[:document.effects/load-and-sync doc-path data-dir]]))
