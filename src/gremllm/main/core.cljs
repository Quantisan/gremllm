(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.secrets :as secrets]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.menu :as menu]
            [gremllm.main.window :as window]
            [gremllm.main.io :as io]
            [gremllm.schema :as schema]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]))

(defn system-info [secrets encryption-available?]
  {:encryption-available? encryption-available?
   :secrets               (secrets/redact-all-string-values secrets)})

(defn- setup-close-handlers
  "Setup coordinated quit/close handlers for unsaved changes protection (stub)."
  [^js main-window]
  (let [closing? (atom false)
        quitting? (atom false)]

    ;; Intercept app quit
    (.on app "before-quit"
         (fn [event]
           (when-not @closing?
             (.preventDefault event)
             (reset! quitting? true)
             (js/console.log "Quit intercepted!")
             (.close main-window))))

    ;; Intercept window close
    (.on main-window "close"
         (fn [event]
           (when-not @closing?
             (.preventDefault event)
             (reset! closing? true)
             (js/console.log "Close intercepted - closing now...")
             ;; TODO: Send notification to renderer for unsaved check
             (.destroy main-window)
             (when @quitting?
               (js/console.log "Now quitting app...")
               (.quit app)))))))

(defn create-window []
  (let [dimensions (window/calculate-window-dimensions window/window-dimension-specs)
        preload-path (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"]
    (.loadFile main-window html-path)
    main-window))

(defn register-domain-handlers
  "Register IPC handlers for core domain operations:
   - Chat: LLM message exchange
   - Topics: Save/load/list conversation threads
   - Workspace: Bulk topic operations
   - Secrets: Secure configuration storage
   - System: Runtime capability detection"
  [store workspace-dir secrets-filepath]
  (.on ipcMain "chat/send-message"
       (fn [event request-id messages]
         (let [messages-clj (js->clj messages :keywordize-keys true)]
           (nxr/dispatch store {:ipc-event event
                                :request-id request-id
                                :channel "chat/send-message"}
                         [[:chat.effects/send-message messages-clj [:env/anthropic-api-key]]]))))

  (let [topics-dir (io/topics-dir-path workspace-dir)]
    (.handle ipcMain "workspace/load-folder"
             (fn [_event]
               (-> (topic-effects/load-all topics-dir)
                   (clj->js))))

    (.handle ipcMain "topic/save"
              (fn [_event topic-data]
                (-> (js->clj topic-data :keywordize-keys true)
                    (schema/topic-from-disk)
                    (topic-actions/topic->save-plan topics-dir)
                    (topic-effects/save))))

    (.handle ipcMain "topic/load-latest"
              (fn [_event]
                (topic-effects/load-latest topics-dir)))

    (.handle ipcMain "topic/list"
              (fn [_event]
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
    (register-domain-handlers store workspace-dir secrets-filepath)
    (menu/create-menu store)))

(defn- initialize-app [store]
  (setup-system-resources store)
  (-> (create-window)
      (setup-close-handlers)))

(defn- handle-app-activate
  "macOS: Fired when app activated (dock clicked). Creates window if none exist."
  [_store]
  (when (zero? (.-length (.getAllWindows BrowserWindow)))
    (-> (create-window)
        (setup-close-handlers))))

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

