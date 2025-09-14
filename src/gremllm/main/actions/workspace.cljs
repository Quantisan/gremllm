(ns gremllm.main.actions.workspace
  (:require [gremllm.main.state :as state]))

(defn set-directory [_state dir]
  [[:store.effects/save state/workspace-dir-path dir]])

(defn open [_state workspace-folder-path]
  [[:store.effects/save state/workspace-dir-path workspace-folder-path]
   [:workspace.effects/load-and-sync workspace-folder-path]])
