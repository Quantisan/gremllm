(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.renderer.state.acp :as acp-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn- assistant-message? [message]
  (= :assistant (:type message)))

(defn append-to-response
  "Appends chunk to the last assistant message (streaming continuation)."
  [state chunk-text]
  ;; TODO: check that acp-session-id of the chunk is same as that of the topic's
  (let [topic-id (topic-state/get-active-topic-id state)
        messages (topic-state/get-messages state)
        last-idx (dec (count messages))
        new-text (str (:text (peek messages)) chunk-text)
        msg-path (conj (topic-state/topic-field-path topic-id :messages) last-idx :text)]
    [:effects/save msg-path new-text]))

(defn- start-response
  "Creates a new assistant message (first chunk of a new turn)."
  [chunk-text message-id]
  ;; TODO: DRY with actions.messages/add-message
  [[:messages.actions/append-to-state {:id message-id :type :assistant :text chunk-text}]
   [:topic.actions/mark-active-unsaved]
   [:ui.actions/scroll-chat-to-bottom]])

(defn streaming-chunk-effects
  "Builds effects for an incoming assistant message chunk.
   Appends to existing response or starts a new one."
  [state chunk-text message-id]
  (let [continuing? (assistant-message? (peek (topic-state/get-messages state)))]
    (if continuing?
      [[:effects/save acp-state/loading-path false]
       (append-to-response state chunk-text)]
      (into [[:effects/save acp-state/loading-path false]]
            (start-response chunk-text message-id)))))

(defn session-update
  "Handles incoming ACP session updates (streaming chunks, errors, etc)."
  [state {:keys [update]}]
  (when (= (:session-update update) :agent-message-chunk)
    (let [chunk-text (get-in update [:content :text]) ;; TODO: refactor and link with integration test to ensure data schema
          message-id (.now js/Date)]
      (streaming-chunk-effects state chunk-text message-id))))

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
         :on-success [[:loading.actions/set-loading? topic-id false]
                      [:topic.actions/auto-save topic-id]]
         :on-error   [[:loading.actions/set-loading? topic-id false]]}]]
      (js/console.error "[ACP] No session for prompt"))))
