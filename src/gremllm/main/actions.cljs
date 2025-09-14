(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.actions.menu :as menu-actions]
            [gremllm.main.actions.workspace :as workspace-actions]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.effects.workspace :as workspace-effects]
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

;; Menu Actions Registration
;; =========================
;; Menu IPC Flow in Electron:
;; 1. User clicks menu item → Menu (main process) dispatches action
;; 2. Action returns effect to send command to renderer
;; 3. Effect uses IPC to notify renderer's focused window
;; 4. Renderer receives command via ipcRenderer.on("menu:command", ...)
;; 5. Renderer dispatches its own actions to handle the command
;;
;; This indirection exists because in Electron's architecture:
;; - Menus are created in the main process (for native OS integration)
;; - Application state lives in the renderer process (where the UI is)
;; - We bridge this gap with IPC, maintaining clean separation

(nxr/register-action! :menu.actions/save-topic menu-actions/save-topic)
(nxr/register-action! :menu.actions/show-settings menu-actions/show-settings)
(nxr/register-action! :menu.actions/open-folder menu-actions/open-folder)

;; Store Effects
;; =============
;; General store mutation effect following FCIS principles

(nxr/register-effect! :store.effects/save
  (fn [_ store path value]
    (swap! store assoc-in path value)))

;; IPC Effects Registration
;; ========================
;; All IPC boundary effects are implemented in main.effects.ipc
;; to maintain clear FCIS separation.

(nxr/register-effect! :ipc.effects/send-to-renderer
  (fn [_ _ channel data]
    (ipc-effects/send-to-renderer channel (clj->js data))))

(nxr/register-effect! :menu.effects/send-command
  (fn [_ _ command]
    (ipc-effects/send-to-renderer "menu:command" (name command))))

(nxr/register-effect! :ipc.effects/reply ipc-effects/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-effects/reply-error)
(nxr/register-effect! :ipc.effects/promise->reply ipc-effects/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages api-key)]])))

;; Workspace Actions/Effects Registration
(nxr/register-action! :workspace.actions/set-directory workspace-actions/set-directory)
(nxr/register-action! :workspace.actions/open workspace-actions/open)

(nxr/register-effect! :workspace.effects/pick-folder-dialog workspace-effects/pick-folder-dialog)
(nxr/register-effect! :workspace.effects/load-and-sync workspace-effects/load-and-sync)

