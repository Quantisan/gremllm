(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.topic :as topic-state]))

(defn session-ready
  "Session created successfully. Save session-id to topic."
  [_state topic-id session-id]
  (js/console.log "[ACP] Session ready:" session-id "for topic:" topic-id)
  [[:effects/save (topic-state/session-id-path topic-id) session-id]])

(defn session-error
  "ACP session initialization failed."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error))

(defn new-session [_state topic-id]
  [[:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready topic-id]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn send-prompt [state text]
  (let [session-id (topic-state/get-session-id state)]
    (if session-id
      [[:effects/promise
        {:promise (.acpPrompt js/window.electronAPI session-id text)}]]
      (js/console.error "[ACP] No session for prompt"))))

