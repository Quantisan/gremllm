(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.secrets :as secrets]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.menu :as menu]
            [gremllm.main.window :as window]
            [gremllm.main.io :as io]
            [gremllm.main.state :as state]
            [gremllm.schema :as schema]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]))

(defn system-info [secrets encryption-available?]
  {:encryption-available? encryption-available?
   :secrets               (secrets/redact-all-string-values secrets)})

(defn register-domain-handlers
  "Register IPC handlers for core domain operations:
   - Chat: LLM message exchange
   - Topics: Save/load/list conversation threads
   - Workspace: Bulk topic operations
   - Secrets: Secure configuration storage
   - System: Runtime capability detection"
  [store secrets-filepath]
  (.on ipcMain "chat/send-message"
       (fn [event request-id messages]
         (let [messages-clj (js->clj messages :keywordize-keys true)]
           (nxr/dispatch store {:ipc-event event
                                :request-id request-id
                                :channel "chat/send-message"}
                         [[:chat.effects/send-message messages-clj [:env/anthropic-api-key]]]))))

  (.handle ipcMain "topic/save"
           (fn [_event topic-data]
             (let [workspace-dir (state/get-workspace-dir @store)]
               (-> (js->clj topic-data :keywordize-keys true)
                   (schema/topic-from-ipc)
                   (topic-actions/topic->save-plan (io/topics-dir-path workspace-dir))
                   (topic-effects/save)))))

  (.handle ipcMain "workspace/load-folder"
           (fn [_event]
             (let [workspace-dir (state/get-workspace-dir @store)
                   topics-dir (io/topics-dir-path workspace-dir)]
               (-> (topic-effects/load-all topics-dir)
                   (clj->js)))))

  (.handle ipcMain "topic/load-latest"
            (fn [_event]
              (let [workspace-dir (state/get-workspace-dir @store)
                    topics-dir (io/topics-dir-path workspace-dir)]
                (topic-effects/load-latest topics-dir))))

  (.handle ipcMain "topic/list"
            (fn [_event]
              (let [workspace-dir (state/get-workspace-dir @store)
                    topics-dir (io/topics-dir-path workspace-dir)]
                (-> (topic-effects/enumerate topics-dir)
                    (clj->js)))))

  ;; Secrets handlers - call functions directly at the boundary
  (.handle ipcMain "secrets/save"
           (fn [_event key value]
             (secrets/save secrets-filepath (keyword key) value)))

  (.handle ipcMain "secrets/delete"
           (fn [_event key]
             (secrets/del secrets-filepath (keyword key))))

  (.handle ipcMain "system/get-info"
           (fn [_event]
             (-> (system-info
                   (secrets/load-all secrets-filepath)
                   (secrets/check-availability))
                 (clj->js)))))

(defn- setup-system-resources [store]
  (let [user-data-dir   (.getPath app "userData")
        workspace-dir   (io/workspace-dir-path user-data-dir)
        secrets-filepath (io/secrets-file-path user-data-dir)]
    ;; Initialize workspace-dir in store
    (nxr/dispatch store {} [[:workspace.actions/set-directory workspace-dir]])

    (register-domain-handlers store secrets-filepath)
    (menu/create-menu store)))

(defn- initialize-app [store]
  (setup-system-resources store)
  (-> (window/create-window)
      (window/setup-close-handlers)))

(defn- handle-app-activate
  "macOS: Fired when app activated (dock clicked). Creates window if none exist."
  [_store]
  (when (zero? (.-length (.getAllWindows BrowserWindow)))
    (-> (window/create-window)
        (window/setup-close-handlers))))

(defn- handle-window-all-closed
  "Quit on Windows/Linux when last window closes. macOS apps stay running."
  []
  (when-not (= (.-platform js/process) "darwin")
    (.quit app)))

(defn main []
  (let [store (atom {})]
    (.on app "ready" #(initialize-app store))
    (.on app "activate" #(handle-app-activate store))
    (.on app "window-all-closed" handle-window-all-closed)))

