(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.menu :as menu]
            [gremllm.main.utils :refer [nxr-result]]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]
            ["electron-reload" :as electron-reload]
            ["path" :as path]))

(electron-reload (.cwd js/process) #js {:ignored #"node_modules|[/\\]\.|target"})

(defn create-window []
  (let [win (BrowserWindow.
              (clj->js {:width 800
                        :height 600
                        :webPreferences {:preload (.join path js/__dirname "../resources/public/js/preload.js")}}))]
    (.loadFile win "resources/public/index.html")))

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

  ;; NOTE: Electron IPC handlers must return values. We're abusing Nexus effects to return values
  ;; (not idiomatic) with nxr-result because I'd like to maintain FCIS architecture.
  (.handle ipcMain "topic/save"
          (fn [_event topic-data]
            (let [topic-clj (js->clj topic-data :keywordize-keys true)
                  dispatch-result (nxr/dispatch store {} [[:ipc.effects/save-topic topic-clj (topics-dir)]])]
              (nxr-result dispatch-result))))

  (.handle ipcMain "topic/load"
           (fn [_event]
             (let [dispatch-result (nxr/dispatch store {} [[:ipc.effects/load-topic (topics-dir)]])]
               (nxr-result dispatch-result)))))


(defn main []
  ;; Load .env file when running in development (not packaged)
  (when-not (.-isPackaged app)
    (println "[INFO] Running in development mode - loading .env file")
    (.config (js/require "dotenv") #js {:override true}))

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

