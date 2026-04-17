(ns gremllm.renderer.actions.messages
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]))

(defn- base-add-message-effects [topic-id message]
  [[:messages.actions/append-to-state topic-id message]
   [:topic.actions/mark-unsaved topic-id]
   [:ui.actions/scroll-chat-to-bottom]])

(defn add-message [_state topic-id message]
  (into (base-add-message-effects topic-id message)
        [;; TODO: we should not save if the last message was an Error
         [:topic.effects/auto-save topic-id]]))

(defn add-message-no-save [_state topic-id message]
  (base-add-message-effects topic-id message))

(defn build-conversation-with-new-message
  "Builds complete conversation history including the new user message."
  [state topic-id new-user-message]
  (let [message-history (topic-state/get-topic-field state topic-id :messages)]
    (conj (or message-history []) new-user-message)))

(defn append-to-state [state topic-id new-user-message]
  (if (topic-state/get-topic state topic-id)
    (let [path-to-messages (topic-state/topic-field-path topic-id :messages)
          messages-to-save (build-conversation-with-new-message state topic-id new-user-message)]
      [[:effects/save path-to-messages messages-to-save]])
    (throw (js/Error. "Cannot append message: topic not found"))))
