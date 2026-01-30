(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.topic :as topic-state]))

(defn session-ready
  "Session created successfully. Save acp-session-id to topic."
  [_state topic-id acp-session-id]
  (js/console.log "[ACP] Session ready:" acp-session-id "for topic:" topic-id)
  [[:loading.actions/set-loading? topic-id false]
   [:effects/save (topic-state/acp-session-id-path topic-id) acp-session-id]])

(defn session-error
  "ACP session initialization failed."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error))

(defn new-session [_state topic-id]
  [[:loading.actions/set-loading? topic-id true]
   [:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready topic-id]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn send-prompt [state text]
  (let [acp-session-id (topic-state/get-acp-session-id state)
        topic-id       (topic-state/get-active-topic-id state)]
    (if acp-session-id
      [[:loading.actions/set-loading? topic-id true]
       [:effects/promise
        {:promise    (.acpPrompt js/window.electronAPI acp-session-id text)
         :on-success [[:loading.actions/set-loading? topic-id false]]
         :on-error   [[:loading.actions/set-loading? topic-id false]]}]]
      (js/console.error "[ACP] No session for prompt"))))

