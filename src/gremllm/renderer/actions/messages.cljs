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
         [:topic.effects/auto-save]]))

(defn add-message-no-save [_state message]
  (base-add-message-effects message))

(defn build-conversation-with-new-message
  "Builds complete conversation history including the new user message."
  [state new-user-message]
  (let [message-history (topic-state/get-messages state)]
    (conj (or message-history []) new-user-message)))

(defn append-to-state [state new-user-message]
  (if-let [active-id (topic-state/get-active-topic-id state)]
    (let [path-to-messages (topic-state/topic-field-path active-id :messages)
          messages-to-save (build-conversation-with-new-message state new-user-message)]
      [[:effects/save path-to-messages messages-to-save]])
    (throw (js/Error. "Cannot append message: no active topic."))))
