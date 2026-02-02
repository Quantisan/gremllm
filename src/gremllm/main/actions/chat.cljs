(ns gremllm.main.actions.chat
  ;; DEPRECATED [pre-acp] - direct LLM flow replaced by ACP
  "Pure action functions for chat domain operations in the main process."
  (:require [gremllm.main.state :as state]
            [gremllm.schema :as schema]))

(defn send-message-from-ipc
  [state {:keys [messages model api-key attachment-paths reasoning]}]
  (let [workspace-dir    (state/get-workspace-dir state)
        has-attachments? (and attachment-paths (seq attachment-paths) workspace-dir)]
    (js/console.log "[chat:route]"
                    (clj->js {:path (if has-attachments? "with-attachments" "direct")
                              :attachments-count (count (or attachment-paths []))
                              :reasoning reasoning}))
    (if has-attachments?
      ;; Has attachments - dispatch orchestrating action
      [[:chat.actions/send-message-with-attachments
        {:workspace-dir workspace-dir
         :attachment-paths attachment-paths
         :messages messages
         :model model
         :api-key api-key
         :reasoning reasoning}]]
      ;; No attachments - normal flow
      [[:chat.effects/send-message (schema/messages->chat-api-format messages) model api-key reasoning]])))

(defn send-message-with-attachments
  [_state {:keys [workspace-dir attachment-paths messages model api-key reasoning]}]
  [[:attachment.effects/prepare-for-send
    {:workspace-dir workspace-dir
     :file-paths attachment-paths
     :messages messages
     :model model
     :api-key api-key
     :reasoning reasoning}]])

(defn enrich-last-message-with-attachments
  "Adds attachments to the last message in the conversation.
  Note: Assumes last message is the user message - coupled to renderer/build-conversation-with-new-message."
  [messages attachments]
  (conj (pop messages)
        (assoc (peek messages) :attachments attachments)))

(defn attach-and-send
  "Pure: transforms loaded attachment data to API format, enriches messages, returns send effect.
  Receives vector of [AttachmentRef Buffer] pairs from load effect."
  [_state {:keys [loaded-pairs messages model api-key reasoning]}]
  (let [;; Pure: transform each ref+content pair to API format
        api-attachments (mapv (fn [[ref content]]
                                (schema/attachment-ref->api-format ref content))
                              loaded-pairs)
        ;; Pure: enrich last message with API-ready attachments
        enriched-messages (enrich-last-message-with-attachments messages api-attachments)]
    (js/console.log "[chat:enrich]"
                    (clj->js {:attachments (mapv (fn [att]
                                                   {:mime-type (:mime-type att)
                                                    :data-bytes (count (:data att))})
                                                 api-attachments)}))
    ;; Return effect to send enriched messages
    [[:chat.effects/send-message (schema/messages->chat-api-format enriched-messages) model api-key reasoning]]))
