(ns gremllm.main.actions.chat
  "Pure action functions for chat domain operations in the main process."
  (:require [gremllm.main.state :as state]))

(defn send-message-from-ipc
  "Handles chat message send requests from IPC boundary.

  Takes messages, model identifier, API key (resolved from environment),
  and optional file-paths for attachments.
  Returns effect description to send message to LLM provider."
  [state messages model api-key file-paths]
  (let [workspace-dir (state/get-workspace-dir state)]
    (if (and file-paths (seq file-paths) workspace-dir)
      ;; Has attachments - process them first, then send
      [[:chat.effects/send-message-with-attachments workspace-dir file-paths messages model api-key]]
      ;; No attachments - normal flow
      [[:chat.effects/send-message messages model api-key]])))
