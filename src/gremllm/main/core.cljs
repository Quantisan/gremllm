(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.secrets :as secrets]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.menu :as menu]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]
            ["path" :as path]))

(def ^:private default-window-width 800)
(def ^:private default-window-height 600)

(defn system-info [secrets encryption-available?]
  {:encryption-available? encryption-available?
   :secrets               (secrets/redact-all-string-values secrets)})

(defn create-window []
  (let [preload-path (.join path js/__dirname "../resources/public/js/preload.js")
        window-config {:width default-window-width
                       :height default-window-height
                       :webPreferences {:preload preload-path}}
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"]
    (.loadFile main-window html-path)
    main-window))

(defn topics-dir []
  (.join path (.getPath app "userData") "topics"))

(defn setup-api-handlers [store]
  (.on ipcMain "chat/send-message"
       (fn [event request-id messages]
         (let [messages-clj (js->clj messages :keywordize-keys true)]
           (nxr/dispatch store {:ipc-event event
                                :request-id request-id
                                :channel "chat/send-message"}
                         [[:chat.effects/send-message messages-clj [:env/anthropic-api-key]]]))))

  ;; Topic handlers - call functions directly at the boundary
  (.handle ipcMain "topic/save"
          (fn [_event topic-data]
            (let [topic-clj (js->clj topic-data :keywordize-keys true)
                  save-plan (topic-actions/prepare-save nil nil topic-clj (topics-dir))]
              (topic-effects/save save-plan))))

  (.handle ipcMain "topic/load"
           (fn [_event]
             (topic-effects/load (topics-dir) topic-actions/topic-file-pattern)))

  ;; Secrets handlers - call functions directly at the boundary
  (.handle ipcMain "secrets/save"
           (fn [_event key value]
             (secrets/save nil nil (keyword key) value)))

  (.handle ipcMain "secrets/delete"
           (fn [_event key]
             (secrets/del nil nil (keyword key))))

  (.handle ipcMain "system/get-info"
           (fn [_event]
             (let [secrets               (secrets/load-all nil nil)
                   encryption-available? (secrets/check-availability)]
               (-> (system-info secrets encryption-available?)
                   (clj->js))))))


(defn main []
  (let [store (atom {})]
    (setup-api-handlers store)
    (-> (.whenReady app)
        (.then (fn []
                 (create-window)
                 (menu/create-menu store)
                 (.on app "activate"
                      #(when (zero? (.-length (.getAllWindows BrowserWindow)))
                         (create-window))))))

    (.on app "window-all-closed"
        #(when-not (= (.-platform js/process) "darwin")
            (.quit app)))))

