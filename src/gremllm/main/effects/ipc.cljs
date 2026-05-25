(ns gremllm.main.effects.ipc
  "IPC Effects - The Imperative Shell for Process Communication
   =============================================================
   All effects that cross the main/renderer process boundary.
   This is our explicit imperative shell for Electron's IPC architecture."
  (:require [gremllm.main.electron :as electron]))

(defn send-to-renderer
  "Send a command to the focused renderer window.
   Used for menu commands and other main→renderer communication."
  [channel data]
  (when-let [BrowserWindow (electron/get-browser-window)]
    (when-let [^js window (.getFocusedWindow BrowserWindow)]
      (some-> window
              .-webContents
              (.send channel data)))))

(defn reply
  "Reply to an IPC request with success"
  [{:keys [dispatch-data]} _ result]
  (when-let [event (:ipc-event dispatch-data)]
    (let [ipc-correlation-id (:ipc-correlation-id dispatch-data)
          channel (:channel dispatch-data)]
      (.send (.-sender event) (str channel "-success-" ipc-correlation-id) result))))

(defn reply-error
  "Reply to an IPC request with error"
  [{:keys [dispatch-data]} _ error]
  (when-let [event (:ipc-event dispatch-data)]
    (let [ipc-correlation-id (:ipc-correlation-id dispatch-data)
          channel (:channel dispatch-data)
          error-msg (or (.-message error) (str error))]
      (js/console.error "IPC request failed:"
                        (clj->js {:channel channel
                                  :ipcCorrelationId ipc-correlation-id
                                  :error error-msg}))
      (.send (.-sender event) (str channel "-error-" ipc-correlation-id) error-msg))))

(defn promise->reply
  "Convert promise result to IPC reply"
  [{:keys [dispatch]} _ promise]
  (-> promise
      (.then #(dispatch [[:ipc.effects/reply (clj->js %)]]))
      (.catch #(dispatch [[:ipc.effects/reply-error %]]))))
