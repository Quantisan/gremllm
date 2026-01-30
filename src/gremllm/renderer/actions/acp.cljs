(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.acp :as acp-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn- assistant-message? [message]
  (= :assistant (:type message)))

(defn agent-message-chunk-effects
  "Builds effects for appending or creating an assistant message from a chunk."
  [state chunk-text message-id]
  (let [topic-id (topic-state/get-active-topic-id state)
        messages (topic-state/get-messages state)
        last-msg (peek messages)]
    (if (assistant-message? last-msg)
      ;; TODO: refactor out the two branches into domain-obvious fns
      (let [last-idx (dec (count messages))
            new-text (str (:text last-msg) chunk-text)
            msg-path (conj (topic-state/topic-field-path topic-id :messages) last-idx :text)]
        [[:effects/save acp-state/loading-path false]
         [:effects/save msg-path new-text]])
      [[:effects/save acp-state/loading-path false]
       [:messages.actions/add-to-chat {:id   message-id
                                       :type :assistant
                                       :text chunk-text}]])))

(defn session-update [state {:keys [update]}]
    (when (= (:session-update update) :agent-message-chunk)
      (let [chunk-text (get-in update [:content :text]) ;; TODO: refactor and add test
            message-id (.now js/Date)]
        (agent-message-chunk-effects state chunk-text message-id))))

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
  [[:loading.actions/set-loading? topic-id true] ;; TODO: this is confusingly overlapping with state.acp/loading-path
   [:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready topic-id]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn send-prompt [state text]
  (let [topic-id       (topic-state/get-active-topic-id state)
        acp-session-id (topic-state/get-acp-session-id state topic-id)]
    (if acp-session-id
      [[:loading.actions/set-loading? topic-id true]
       [:effects/promise
        {:promise    (.acpPrompt js/window.electronAPI acp-session-id text)
         :on-success [[:loading.actions/set-loading? topic-id false]]
         :on-error   [[:loading.actions/set-loading? topic-id false]]}]]
      (js/console.error "[ACP] No session for prompt"))))
