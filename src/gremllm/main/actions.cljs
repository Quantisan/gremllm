(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.actions.ipc :as ipc-actions]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.io :as io]
            ["electron/main" :refer [BrowserWindow app]]))

;; Register how to extract state from the system
(nxr/register-system->state! deref)

;; Placeholder for environment variables
(nxr/register-placeholder! :env/anthropic-api-key
  (fn [_]
    (or (.-ANTHROPIC_API_KEY (.-env js/process))
        (some-> (.getPath app "userData")
                (io/secrets-file-path)
                (secrets-actions/load :anthropic-api-key)
                :ok))))

;; Electron platform helpers (used by effects)
(defn send-to-focused-window [channel]
  (some-> (.getFocusedWindow BrowserWindow)
          .-webContents
          (.send channel)))

;; Effects
(nxr/register-effect! :effects/trigger-save-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "topic/save")))

(nxr/register-effect! :effects/trigger-settings-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "menu:settings")))

(nxr/register-effect! :ipc.effects/reply ipc-actions/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-actions/reply-error)

;; Generic promise handling
(nxr/register-effect! :ipc.effects/promise->reply ipc-actions/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages api-key)]])))

