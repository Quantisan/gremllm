(ns gremllm.main.actions.workspace
  (:require [gremllm.main.state :as state]))

(defn set-active-document [_state doc-path]
  [[:store.effects/save state/active-document-path doc-path]
   [:workspace.effects/load-and-sync doc-path]])

(defn pick-document [_state]
  [[:workspace.effects/pick-document-dialog]])

(defn reload [state]
  [[:workspace.effects/load-and-sync (state/get-active-document-path state)]])
