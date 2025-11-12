(ns gremllm.main.actions.chat
  "Pure action functions for chat domain operations in the main process."
  (:require [gremllm.main.state :as state]
            [gremllm.schema :as schema]))

(defn send-message-from-ipc
  "Handles chat message send requests from IPC boundary.

  Takes messages, model identifier, API key (resolved from environment),
  and optional file-paths for attachments.
  Returns effect description to send message to LLM provider."
  [state messages model api-key file-paths]
  (let [workspace-dir (state/get-workspace-dir state)]
    (if (and file-paths (seq file-paths) workspace-dir)
      ;; Has attachments - dispatch orchestrating action
      [[:chat.actions/send-message-with-attachments workspace-dir file-paths messages model api-key]]
      ;; No attachments - normal flow
      [[:chat.effects/send-message messages model api-key]])))

(defn send-message-with-attachments
  "Orchestrates attachment processing flow: process files → load content → enrich → send.
  Pure action that returns effect description to start the flow."
  [state workspace-dir file-paths messages model api-key]
  [[:attachment.effects/process-batch-then-continue workspace-dir file-paths messages model api-key]])

(defn send-message-with-loaded-attachments
  "Orchestrates loading attachment content and sending.
  Receives AttachmentRefs from previous step, returns effect to load content."
  [state workspace-dir attachment-refs messages model api-key]
  [[:attachment.effects/load-then-enrich workspace-dir attachment-refs messages model api-key]])

(defn enrich-and-send
  "Pure: transforms loaded attachment data to API format, enriches messages, returns send effect.
  Receives vector of [AttachmentRef Buffer] pairs from load effect."
  [state loaded-pairs messages model api-key]
  (let [;; Pure: transform each ref+content pair to API format
        api-attachments (mapv (fn [[ref content]]
                                (schema/attachment-ref->api-format ref content))
                              loaded-pairs)
        ;; Pure: enrich first message with API-ready attachments
        enriched-messages (update messages 0 assoc :attachments api-attachments)]
    ;; Return effect to send enriched messages
    [[:chat.effects/send-message enriched-messages model api-key]]))
