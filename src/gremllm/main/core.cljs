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
  ;; Test different ways to detect development mode
  (println "\n=== Development Detection Tests ===")
  (println "1. NODE_ENV:" (.-NODE_ENV (.-env js/process)))
  (println "2. app.isPackaged:" (.-isPackaged app))
  (println "3. process.argv:" (.-argv js/process))
  (println "4. process.execPath:" (.-execPath js/process))
  (println "5. __dirname:" js/__dirname)
  (println "6. process.cwd():" (.cwd js/process))
  (println "7. process.env keys sample:" (take 10 (js/Object.keys (.-env js/process))))
  (println "8. process.defaultApp:" (.-defaultApp js/process))
  (println "9. app.getName():" (.getName app))
  (println "10. app.getVersion():" (.getVersion app))
  (println "================================\n")

  ;; Try loading dotenv with the most reliable method
  (when-not (.-isPackaged app)
    (println ">>> App is NOT packaged - loading .env file")
    (.config (js/require "dotenv"))
    (println ">>> ANTHROPIC_API_KEY present?:" (boolean (.-ANTHROPIC_API_KEY (.-env js/process)))))

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

