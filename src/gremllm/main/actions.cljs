(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.actions.workspace :as workspace-actions]
            [gremllm.main.llm :as llm]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.effects.workspace :as workspace-effects]
            [gremllm.main.io :as io]
            [gremllm.schema :as schema]
            ["electron/main" :refer [app]]))

;; Register how to extract state from the system
(nxr/register-system->state! deref)

;; Environment Variable Placeholders
;; ==================================
;; PRAGMATIC FCIS EXCEPTION: This placeholder performs synchronous file I/O
;; to support safeStorage fallback. This is the only exception to strict FCIS
;; in the codebase. Acceptable because: (1) reading env state is conceptually
;; like process.env access, (2) synchronous/deterministic, (3) isolated to API
;; key resolution only.

(nxr/register-placeholder! :env/api-key-for-model
  (fn [_dispatch-data model]
    (try
      (let [provider (schema/model->provider model)
            env-var-name (llm/provider->env-var-name provider)
            storage-key (schema/provider->api-key-keyword provider)]
        (or (aget (.-env js/process) env-var-name)
            (some-> (.getPath app "userData")
                    (io/secrets-file-path)
                    (secrets-actions/load storage-key)
                    :ok)))
      (catch :default e
        (js/console.error "Error resolving API key for model" model ":" e)
        (throw e)))))

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

(nxr/register-action! :menu.actions/save-topic (fn [_state] [[:menu.effects/send-command :save-topic]]))
(nxr/register-action! :menu.actions/show-settings (fn [_state] [[:menu.effects/send-command :show-settings]]))
(nxr/register-action! :menu.actions/open-folder (fn [_state] [[:workspace.actions/pick-folder]]))

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
  (fn [{:keys [dispatch]} _ messages model api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages model api-key)]])))

;; Workspace Actions/Effects Registration
(nxr/register-action! :workspace.actions/set-directory workspace-actions/set-directory)
(nxr/register-action! :workspace.actions/open-folder workspace-actions/open-folder)
(nxr/register-action! :workspace.actions/pick-folder workspace-actions/pick-folder)

(nxr/register-effect! :workspace.effects/pick-folder-dialog workspace-effects/pick-folder-dialog)
(nxr/register-effect! :workspace.effects/load-and-sync workspace-effects/load-and-sync)

