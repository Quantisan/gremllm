(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.acp :as acp-state]))

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
  [[:effects/save acp-state/session-id-path session-id]])

(defn session-error
  "ACP session initialization failed."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error))

(defn send-prompt
  "Send user message to ACP agent."
  [state text]
  (let [session-id (acp-state/get-session-id state)]
    (if session-id
      [[:effects/save acp-state/loading-path true]
       [:effects/save acp-state/chunks-path []]
       [:effects/promise
        {:promise (.acpPrompt js/window.electronAPI session-id text)}]]

      (do
        (js/console.error "[ACP] No session - cannot send prompt")
        []))))
