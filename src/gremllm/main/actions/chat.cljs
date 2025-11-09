(ns gremllm.main.actions.chat
  "Pure action functions for chat domain operations in the main process.")

(defn send-message-from-ipc
  "Handles chat message send requests from IPC boundary.

  Takes messages, model identifier, and API key (resolved from environment).
  Returns effect description to send message to LLM provider."
  [_state messages model api-key]
  [[:chat.effects/send-message messages model api-key]])
