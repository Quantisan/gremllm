(ns gremllm.main.effects.workspace
  (:require [gremllm.schema :as schema]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.io :as io]))

(defn- get-dialog []
  (when (exists? js/require)
    (try
      (.-dialog (js/require "electron/main"))
      (catch :default _ nil))))

(defn pick-folder-dialog [{:keys [dispatch]} _]
  (when-let [dialog (get-dialog)]
    (-> (.showOpenDialog dialog
                         #js {:title "Open Workspace Folder"
                              :properties #js ["openDirectory"]
                              :buttonLabel "Open"})
        (.then (fn [^js result]
                 (when-not (.-canceled result)
                   (let [workspace-folder-path (first (.-filePaths result))]
                     (dispatch [[:workspace.actions/open-folder workspace-folder-path]]))))))))

(defn load-and-sync [{:keys [dispatch]} _ workspace-folder-path]
  (let [topics-dir (io/topics-dir-path workspace-folder-path)
        topics (topic-effects/load-all topics-dir)
        workspace-data (schema/workspace-sync-for-ipc topics)]
    (dispatch [[:ipc.effects/send-to-renderer "workspace:sync" workspace-data]])))
