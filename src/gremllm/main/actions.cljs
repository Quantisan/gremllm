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

;; Menu Effects (Imperative Shell)
;; ================================
;; This single effect handles the Electron architectural boundary:
;; menus live in main process, application state lives in renderer.
;; This is our explicit imperative shell for crossing that boundary.

(nxr/register-effect! :menu.effects/send-command
  (fn [_ _ command]
    (some-> (.getFocusedWindow BrowserWindow)
            .-webContents
            (.send "menu:command" (name command)))))

(nxr/register-effect! :ipc.effects/reply ipc-actions/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-actions/reply-error)

;; Generic promise handling
(nxr/register-effect! :ipc.effects/promise->reply ipc-actions/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages api-key)]])))

