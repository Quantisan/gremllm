(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions.")

(defn init-session
  "Initialize ACP session. Called on workspace load."
  [_state]
  [[:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn session-ready
  "ACP session has been initialized successfully."
  [_state session-id]
  (js/console.log "[ACP] Session ready:" session-id)
  ;; TODO: Store session-id in state once we need it
  [])

(defn session-error
  "ACP session initialization failed."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error)
  [])
