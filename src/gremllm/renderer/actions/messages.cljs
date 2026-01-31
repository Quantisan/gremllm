(ns gremllm.renderer.actions.messages
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]))

(defn- base-add-message-effects [message]
  [[:messages.actions/append-to-state message]
   [:topic.actions/mark-active-unsaved]
   [:ui.actions/scroll-chat-to-bottom]])

(defn add-message [_state message]
  (into (base-add-message-effects message)
        [;; TODO: we should not save if the last message was an Error
         [:topic.actions/auto-save]]))

(defn add-message-no-save [_state message]
  (base-add-message-effects message))

(defn build-conversation-with-new-message
  "Builds complete conversation history including the new user message.
  Note: New message is appended last - main/enrich-last-message-with-attachments depends on this."
  [state new-user-message]
  (let [message-history (topic-state/get-messages state)]
    (conj (or message-history []) new-user-message)))

(defn append-to-state [state new-user-message]
  (if-let [active-id (topic-state/get-active-topic-id state)]
    (let [path-to-messages (topic-state/topic-field-path active-id :messages)
          messages-to-save (build-conversation-with-new-message state new-user-message)]
      [[:effects/save path-to-messages messages-to-save]])
    (throw (js/Error. "Cannot append message: no active topic."))))

(defn llm-response-received [_state assistant-id response]
  (let [clj-response (js->clj response :keywordize-keys true)]
    [[:loading.actions/set-loading? assistant-id false]
     [:messages.actions/add-to-chat {:id   assistant-id
                                     :type :assistant
                                     :text (:text clj-response)}]]))

(defn llm-response-error [state assistant-id error]
  (js/console.error "Renderer received LLM error:"
                    (clj->js {:assistantId assistant-id
                              :errorMessage (.-message error)
                              :activeTopicId (topic-state/get-active-topic-id state)
                              :messageCount (count (topic-state/get-messages state))}))
  [[:loading.actions/set-loading? assistant-id false]
   [:llm.actions/set-error assistant-id
    (str "Failed to get response: " (or (.-message error) "Network error"))]])

;; Action for sending messages to LLM
;; Note: new-user-message passed explicitly because submit-messages action chain
;; writes message to state and calls this simultaneously - can't read from state reliably
(defn send-messages [state assistant-id model reasoning? new-user-message]
  (let [conversation (build-conversation-with-new-message state new-user-message)
        file-paths (when-let [attachments (seq (form-state/get-pending-attachments state))]
                     (mapv :path attachments))]
    [[:effects/send-llm-messages
      {:messages   conversation
       :model      model
       :reasoning? reasoning?
       :file-paths file-paths
       :on-success [[:llm.actions/response-received assistant-id]]
       :on-error   [[:llm.actions/response-error assistant-id]]}]]))
