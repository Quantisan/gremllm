(ns gremllm.main.actions.workspace
  (:require [gremllm.main.state :as state]))

(defn set-directory [_state dir]
  [[:store.effects/save state/workspace-dir-path dir]])

(defn open-folder [_state workspace-folder-path]
  [[:store.effects/save state/workspace-dir-path workspace-folder-path]
   [:workspace.effects/load-and-sync workspace-folder-path]])

(defn pick-folder [_state]
  [[:workspace.effects/pick-folder-dialog]])

(defn reload [state]
  [[:workspace.effects/load-and-sync (state/get-workspace-dir state)]])
