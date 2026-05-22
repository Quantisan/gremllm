(ns gremllm.main.actions.document
  (:require [gremllm.main.state :as state]))

(defn open [_state doc-path]
  [[:store.effects/save state/active-document-path doc-path]
   [:document.effects/load-and-sync doc-path]])

(defn pick [_state]
  [[:document.effects/pick-dialog]])

(defn reload [state]
  [[:document.effects/load-and-sync (state/get-active-document-path state)]])
