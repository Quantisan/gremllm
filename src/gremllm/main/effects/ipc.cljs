(ns gremllm.main.effects.ipc
  "IPC Effects - The Imperative Shell for Process Communication
   =============================================================
   All effects that cross the main/renderer process boundary.
   This is our explicit imperative shell for Electron's IPC architecture.")

;; Defer the Electron require to runtime to make testing possible
;; Tests run in Node.js, not Electron, so electron/main isn't available
(defn- get-browser-window []
  (when (exists? js/require)
    (try
      (.-BrowserWindow (js/require "electron/main"))
      (catch :default _ nil))))

(defn send-to-renderer
  "Send a command to the focused renderer window.
   Used for menu commands and other main→renderer communication."
  [channel data]
  (when-let [BrowserWindow (get-browser-window)]
    (when-let [^js window (.getFocusedWindow BrowserWindow)]
      (some-> window
              .-webContents
              (.send channel data)))))

(defn reply
  "Reply to an IPC request with success"
  [{:keys [dispatch-data]} _ result]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)
          success-channel (str channel "-success-" request-id)]
      (js/console.log "[IPC Reply] Sending success response:"
                      (clj->js {:channel success-channel
                                :requestId request-id
                                :resultType (type result)}))
      (.send (.-sender event) success-channel result))))

(defn reply-error
  "Reply to an IPC request with error"
  [{:keys [dispatch-data]} _ error]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)
          error-channel (str channel "-error-" request-id)
          error-message (or (.-message error) (str error))]
      (js/console.error "[IPC Reply] Sending error response:"
                        (clj->js {:channel error-channel
                                  :requestId request-id
                                  :errorMessage error-message
                                  :errorType (type error)}))
      (.send (.-sender event) error-channel error-message))))

(defn promise->reply
  "Convert promise result to IPC reply"
  [{:keys [dispatch]} _ promise]
  (js/console.log "[IPC Promise] Waiting for promise resolution...")
  (-> promise
      (.then (fn [result]
               (js/console.log "[IPC Promise] Promise resolved successfully:"
                               (clj->js {:resultKeys (when (object? result) (js/Object.keys result))
                                         :resultType (type result)}))
               (dispatch [[:ipc.effects/reply (clj->js result)]])))
      (.catch (fn [error]
                (js/console.error "[IPC Promise] Promise rejected:"
                                  (clj->js {:errorMessage (.-message error)
                                            :errorStack (.-stack error)}))
                (dispatch [[:ipc.effects/reply-error error]])))))
