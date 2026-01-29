(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.acp :as acp-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn init-session
  "Initialize ACP session for active topic. Called on first prompt."
  [_state]
  [[:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn session-ready
  "ACP session has been initialized. Store in active topic."
  [state session-id]
  (js/console.log "[ACP] Session ready:" session-id)
  (if-let [topic-id (topic-state/get-active-topic-id state)]
    [[:effects/save (topic-state/session-id-path topic-id) session-id]]
    (do
      (js/console.error "[ACP] No active topic for session")
      [])))

(defn session-error
  "ACP session initialization failed."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error))

(defn send-prompt
  "Send user message to ACP agent. Creates session lazily if needed."
  [state text]
  (let [session-id (topic-state/get-session-id state)]
    (if session-id
      [[:effects/save acp-state/loading-path true]
       [:effects/promise
        {:promise (.acpPrompt js/window.electronAPI session-id text)}]]
      ;; No session yet - create one, then send prompt
      [[:effects/save acp-state/loading-path true]
       [:effects/promise
        {:promise    (.acpNewSession js/window.electronAPI)
         :on-success [[:acp.actions/session-ready-then-prompt text]]
         :on-error   [[:acp.actions/session-error]]}]])))

(defn session-ready-then-prompt
  "Session created, now send the pending prompt."
  [state session-id text]
  (js/console.log "[ACP] Session ready, sending prompt:" session-id)
  (if-let [topic-id (topic-state/get-active-topic-id state)]
    [[:effects/save (topic-state/session-id-path topic-id) session-id]
     [:effects/promise
      {:promise (.acpPrompt js/window.electronAPI session-id text)}]]
    (do
      (js/console.error "[ACP] No active topic for session")
      [])))
