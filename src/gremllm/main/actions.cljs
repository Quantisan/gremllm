(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.chat :as chat-actions]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.actions.workspace :as workspace-actions]
            [gremllm.main.llm :as llm]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.effects.workspace :as workspace-effects]
            [gremllm.main.effects.attachment :as attachment-effects]
            [gremllm.main.effects.acp :as acp-effects]
            [gremllm.main.io :as io]
            [gremllm.main.window :as window]
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
(nxr/register-action! :menu.actions/new-window (fn [_state] [[:window.actions/create]]))

;; Store Effects
;; =============
;; General store mutation effect following FCIS principles

(nxr/register-effect! :store.effects/save
  (fn [_ store path value]
    (swap! store assoc-in path value)))

;; Window Actions/Effects Registration
;; ===================================

(nxr/register-action! :window.actions/create
  (fn [_state] [[:window.effects/create]]))

(nxr/register-effect! :window.effects/create
  (fn [_ _ _]
    (-> (window/create-window)
        (window/setup-close-handlers))))

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

;; Chat Actions/Effects Registration
(nxr/register-action! :chat.actions/send-message-from-ipc chat-actions/send-message-from-ipc)
(nxr/register-action! :chat.actions/send-message-with-attachments chat-actions/send-message-with-attachments)
(nxr/register-action! :chat.actions/attach-and-send chat-actions/attach-and-send)

(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ api-messages model api-key reasoning]
    (dispatch [[:ipc.effects/promise->reply
                (llm-effects/query-llm-provider api-messages model api-key reasoning)]])))

(nxr/register-effect! :attachment.effects/prepare-for-send
  (fn [{:keys [dispatch]} _store {:keys [workspace-dir file-paths messages model api-key reasoning]}]
    ;; Consolidated I/O: store + load is one domain operation.
    ;; Pure transformation delegated to :chat.actions/attach-and-send.
    (let [refs (attachment-effects/store-all workspace-dir file-paths)
          loaded-pairs (attachment-effects/load-all-content workspace-dir refs)]
      (dispatch [[:chat.actions/attach-and-send
                  {:loaded-pairs loaded-pairs
                   :messages messages
                   :model model
                   :api-key api-key
                   :reasoning reasoning}]]))))

;; Workspace Actions/Effects Registration
(nxr/register-action! :workspace.actions/set-directory workspace-actions/set-directory)
(nxr/register-action! :workspace.actions/open-folder workspace-actions/open-folder)
(nxr/register-action! :workspace.actions/pick-folder workspace-actions/pick-folder)
(nxr/register-action! :workspace.actions/reload workspace-actions/reload)

(nxr/register-effect! :workspace.effects/pick-folder-dialog workspace-effects/pick-folder-dialog)
(nxr/register-effect! :workspace.effects/load-and-sync workspace-effects/load-and-sync)

;; ACP Event Actions
;; =================
;; These handle events dispatched FROM the ACP JS module.
;; The JS client callbacks call dispatcher("acp.events/...", data)
;; which triggers these actions.

(nxr/register-action! :acp.events/session-update
  (fn [state {:keys [session-id update]}]
    ;; For Slice 1: just log. State accumulation in later slices.
    (js/console.log "[ACP] Session update:" session-id (clj->js update))
    []))

;; ACP Effects Registration
;; ========================

(nxr/register-effect! :acp.effects/initialize
  (fn [_ _ _]
    (acp-effects/initialize)))

(nxr/register-effect! :acp.effects/prompt
  (fn [{:keys [dispatch]} _ session-id text]
    (dispatch [[:ipc.effects/promise->reply (acp-effects/prompt session-id text)]])))
