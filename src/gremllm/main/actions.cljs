(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.actions.llm :as llm-actions]
            [gremllm.main.actions.ipc :as ipc-actions]
            ["electron/main" :refer [BrowserWindow]]))

;; Register how to extract state from the system
(nxr/register-system->state! deref)

;; Placeholder for environment variables
(nxr/register-placeholder! :env/anthropic-api-key
  (fn [_]
    (.-ANTHROPIC_API_KEY (.-env js/process))))

;; Electron platform helpers (used by effects)
(defn send-to-focused-window [channel]
  (some-> (.getFocusedWindow BrowserWindow)
          .-webContents
          (.send channel)))

;; Effects
(nxr/register-effect! :effects/trigger-save-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "topic/save")))

(nxr/register-effect! :effects/trigger-open-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "topic/open")))

(nxr/register-effect! :ipc.effects/reply ipc-actions/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-actions/reply-error)

;; Generic promise handling
(nxr/register-effect! :ipc.effects/promise->reply ipc-actions/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-actions/query-llm-provider messages api-key)]])))

(nxr/register-effect! :ipc.effects/save-topic topic-actions/save)
(nxr/register-effect! :ipc.effects/load-topic topic-actions/load)

