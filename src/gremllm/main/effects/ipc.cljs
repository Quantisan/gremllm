(ns gremllm.main.effects.ipc
  "IPC Effects - The Imperative Shell for Process Communication
   =============================================================
   All effects that cross the main/renderer process boundary.
   This is our explicit imperative shell for Electron's IPC architecture."
  (:require ["electron/main" :refer [BrowserWindow]]))

(defn send-to-renderer
  "Send a command to the focused renderer window.
   Used for menu commands and other main→renderer communication."
  [_ _ channel data]
  (some-> (.getFocusedWindow BrowserWindow)
          .-webContents
          (.send channel data)))

(defn reply
  "Reply to an IPC request with success"
  [{:keys [dispatch-data]} _ result]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)]
      (.send (.-sender event) (str channel "-success-" request-id) result))))

(defn reply-error
  "Reply to an IPC request with error"
  [{:keys [dispatch-data]} _ error]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)]
      (.send (.-sender event) (str channel "-error-" request-id)
             (or (.-message error) (str error))))))

(defn promise->reply
  "Convert promise result to IPC reply"
  [{:keys [dispatch]} _ promise]
  (-> promise
      (.then #(dispatch [[:ipc.effects/reply (clj->js %)]]))
      (.catch #(dispatch [[:ipc.effects/reply-error %]]))))
