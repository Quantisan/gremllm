(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.io :as io]
            ["electron/main" :refer [app]]))

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

;; Menu Actions (Pure)
;; ===================
;; Menu items express user intent. The fact that the actual work
;; happens in the renderer is an implementation detail of Electron's
;; architecture, not a domain concept.

(nxr/register-action! :menu.actions/save-topic
  (fn [_state]
    ;; Menu wants to save topic. The topic state lives in renderer,
    ;; so we send the command there via IPC.
    [[:menu.effects/send-command :save-topic]]))

(nxr/register-action! :menu.actions/show-settings
  (fn [_state]
    [[:menu.effects/send-command :show-settings]]))

;; IPC Effects Registration
;; ========================
;; All IPC boundary effects are implemented in main.effects.ipc
;; to maintain clear FCIS separation.

(nxr/register-effect! :menu.effects/send-command
  (fn [_ _ command]
    (ipc-effects/send-to-renderer nil nil "menu:command" (name command))))

(nxr/register-effect! :ipc.effects/reply ipc-effects/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-effects/reply-error)
(nxr/register-effect! :ipc.effects/promise->reply ipc-effects/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages api-key)]])))

