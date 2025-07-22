(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.secrets :as secrets]
            [gremllm.main.menu :as menu]
            [gremllm.main.utils :refer [nxr-result]]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]
            ["path" :as path]))

(defn get-system-info [store]
  ;; WARN: redact all values!
  ;; TODO: using nxr-result is anti-pattern
  (let [secrets-result (nxr-result (nxr/dispatch store {} [[:secrets.effects/load-all]]))]
    {:encryption-available? (secrets/check-availability)
     :secrets               secrets-result}))


(defn create-window [system-info]
  (let [win (BrowserWindow.
              (clj->js {:width 800
                        :height 600
                        :webPreferences {:preload (.join path js/__dirname "../resources/public/js/preload.js")}}))]
    (.loadFile win "resources/public/index.html")

    ;; Push system info after window loads
    (.once (.-webContents win) "did-finish-load"
           (fn []
             (println "[MAIN] Sending system:info:" system-info)
             (.send (.-webContents win) "system:info" (clj->js system-info))))

    win))

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
               (nxr-result dispatch-result))))

  ;; Secrets handlers
  (.handle ipcMain "secrets/save"
           (fn [_event key value]
             (let [dispatch-result (nxr/dispatch store {} [[:secrets.effects/save (keyword key) value]])]
               (nxr-result dispatch-result))))

  (.handle ipcMain "secrets/load"
           (fn [_event key]
             (let [dispatch-result (nxr/dispatch store {} [[:secrets.effects/load (keyword key)]])]
               (nxr-result dispatch-result))))

  (.handle ipcMain "secrets/delete"
           (fn [_event key]
             (let [dispatch-result (nxr/dispatch store {} [[:secrets.effects/delete (keyword key)]])]
               (nxr-result dispatch-result))))

  (.handle ipcMain "secrets/list-keys"
           (fn [_event]
             (let [dispatch-result (nxr/dispatch store {} [[:secrets.effects/list-keys]])]
               (nxr-result dispatch-result))))

  (.handle ipcMain "system/get-info"
           (fn [_event]
             (let [system-info (get-system-info store)]
               (clj->js system-info)))))

(defn main []
  (let [store (atom {})]
    (setup-api-handlers store)
    (-> (.whenReady app)
        (.then (fn []
                 (let [system-info (get-system-info store)]
                   (create-window system-info)
                   (menu/create-menu store)
                   (.on app "activate"
                        #(when (zero? (.-length (.getAllWindows BrowserWindow)))
                           (create-window system-info)))))))

    (.on app "window-all-closed"
        #(when-not (= (.-platform js/process) "darwin")
            (.quit app)))))

