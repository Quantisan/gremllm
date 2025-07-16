(ns gremllm.main.actions.ipc)

(defn reply [{:keys [dispatch-data]} _ result]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)]
      (.send (.-sender event) (str channel "-success-" request-id) result))))

(defn reply-error [{:keys [dispatch-data]} _ error]
  (when-let [event (:ipc-event dispatch-data)]
    (let [request-id (:request-id dispatch-data)
          channel (:channel dispatch-data)]
      (.send (.-sender event) (str channel "-error-" request-id)
             (or (.-message error) (str error))))))

(defn promise->reply [{:keys [dispatch]} _ promise]
    (-> promise
        (.then #(dispatch [[:ipc.effects/reply (clj->js %)]]))
        (.catch #(dispatch [[:ipc.effects/reply-error %]]))))
