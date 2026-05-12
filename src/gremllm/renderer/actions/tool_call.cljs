(ns gremllm.renderer.actions.tool-call
  (:require [gremllm.renderer.state.topic :as topic-state]))

(defn start-tool-call
  "Mint a new tool-call message on the active topic."
  [state message]
  (let [topic-id (topic-state/get-active-topic-id state)]
    [[:messages.actions/add-to-chat-no-save topic-id message]]))

(defn update-tool-call
  "Refine an existing tool-call message by tool-call-id with a patch map.
   Emits one [:effects/save ...] per patch field. Returns nil if no match."
  [state tool-call-id patch]
  (if-let [idx (topic-state/find-message-index-by-tool-call-id state tool-call-id)]
    (let [topic-id (topic-state/get-active-topic-id state)
          msg-path (conj (topic-state/topic-field-path topic-id :messages) idx)]
      (reduce-kv
        (fn [effects field val]
          (conj effects [:effects/save (conj msg-path field) val]))
        []
        patch))
    (do
      (js/console.warn "[ACP] update-tool-call: no message for tool-call-id" tool-call-id)
      nil)))
