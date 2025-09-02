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

(def ^:private window-dimension-specs
  {:width-scale  0.60
   :max-width    1400
   :height-scale 0.80
   :max-height   1000})

(defn system-info [secrets encryption-available?]
  {:encryption-available? encryption-available?
   :secrets               (secrets/redact-all-string-values secrets)})

;; TODO: looks dense; refactor
(defn create-window []
  (let [dimensions (window/calculate-window-dimensions window-dimension-specs)
        preload-path (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"
        closing? (atom false)
        quitting? (atom false)]
    (.loadFile main-window html-path)

    ;; Intercept app quit
    (.on app "before-quit"
         (fn [event]
           (when-not @closing?
             (.preventDefault event)
             (reset! quitting? true)
             (js/console.log "Quit intercepted!")
             ;; Trigger window close, which will handle the delay
             (.close main-window))))

    ;; Intercept window close to check for unsaved changes
    (.on main-window "close"
         (fn [event]
           ;; Only intercept if not already closing
           (when-not @closing?
             (.preventDefault event)
             (reset! closing? true)
             (js/console.log "Close intercepted - closing now...")
             ;; Send notification to renderer (for future use)
             ;; (.send (.-webContents main-window) "check-unsaved-before-close")  ;; TODO:

             ;; Close immediately
             (js/console.log "Now closing window...")
             (.destroy main-window)
             ;; If we were quitting, quit for real now
             (when @quitting?
               (js/console.log "Now quitting app...")
               (.quit app)))))

    main-window))

(defn setup-api-handlers [store workspace-dir secrets-filepath]
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


(defn main []
  (let [store (atom {})]
    (-> (.whenReady app)
        (.then (fn []
                 (let [user-data-dir     (.getPath app "userData")
                       workspace-dir     (io/workspace-dir-path user-data-dir)
                       secrets-filepath  (io/secrets-file-path user-data-dir)]
                   (setup-api-handlers store workspace-dir secrets-filepath))
                 (create-window)
                 (menu/create-menu store)
                 (.on app "activate"
                      #(when (zero? (.-length (.getAllWindows BrowserWindow)))
                         (create-window))))))

    (.on app "window-all-closed"
        #(when-not (= (.-platform js/process) "darwin")
            (.quit app)))))

