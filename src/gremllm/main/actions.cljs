(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.workspace :as workspace-actions]
            [gremllm.main.effects.workspace :as workspace-effects]
            [gremllm.main.effects.acp :as acp-effects]
            [gremllm.main.io :as io]
            [gremllm.main.window :as window]))

;; Register how to extract state from the system
(nxr/register-system->state! deref)

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
  (fn [_state {:keys [acp-session-id _update] :as event-data}]
    ;; WIP: Push to renderer for observability
    (js/console.log "[ACP→IPC] Pushing session update to acp-session-id:" acp-session-id)
    [[:ipc.effects/send-to-renderer "acp:session-update" event-data]]))

;; ACP Effects Registration
;; ========================

(nxr/register-effect! :acp.effects/new-session
  (fn [{:keys [dispatch]} _ cwd]
    (dispatch [[:ipc.effects/promise->reply (acp-effects/new-session cwd)]])))

(nxr/register-effect! :acp.effects/resume-session
  (fn [{:keys [dispatch]} _ cwd acp-session-id]
    (dispatch [[:ipc.effects/promise->reply (acp-effects/resume-session cwd acp-session-id)]])))

(nxr/register-effect! :acp.effects/send-prompt
  (fn [{:keys [dispatch]} _ acp-session-id text workspace-dir]
    (let [maybe-document-path (some-> workspace-dir io/document-file-path)
          maybe-document-path (when (and maybe-document-path (io/file-exists? maybe-document-path))
                                maybe-document-path)
          content-blocks (acp-actions/prompt-content-blocks text maybe-document-path)]
      (dispatch [[:ipc.effects/promise->reply
                  (acp-effects/prompt acp-session-id content-blocks)]]))))
