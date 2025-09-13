(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.schema :as schema]
            [gremllm.main.effects.ipc :as ipc-effects]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.io :as io]
            ["electron/main" :refer [app dialog]]))

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

;; Menu Actions & Effects
;; ======================
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

(nxr/register-action! :menu.actions/save-topic
  (fn [_state]
    ;; Menu wants to save topic. The topic state lives in renderer,
    ;; so we send the command there via IPC.
    [[:menu.effects/send-command :save-topic]]))

(nxr/register-action! :menu.actions/show-settings
  (fn [_state]
    [[:menu.effects/send-command :show-settings]]))

(nxr/register-action! :menu.actions/open-folder
  (fn [_state]
    [[:workspace.effects/pick-and-load-folder]]))

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
    (ipc-effects/send-to-renderer channel data)))

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

;; Dialog effects
(nxr/register-effect! :workspace.effects/pick-and-load-folder
  (fn [{:keys [dispatch]} _]
    ;; TODO: Thinking: should we refactor `:workspace.effects/pick-and-load-folder` to use
    ;; `:ipc.effects/promise->reply` (or a generic version)? I'm thinking about our Design
    ;; Principles. Probably yes, but that means we should move `promise->reply` helper up from
    ;; ipc.effects ns
    (-> (.showOpenDialog dialog
          #js {:title "Open Workspace Folder"
               :properties #js ["openDirectory"]  ; Note: multiSelections is false by default
               :buttonLabel "Open"})
        (.then (fn [^js result]
                 (when-not (.-canceled result)
                   ;; Even with single selection, filePaths is still an array
                   (let [workspace-folder-path (first (.-filePaths result))]
                     (dispatch [[:workspace.effects/load-folder-and-send-to-renderer workspace-folder-path]]))))))))

;; Workspace Actions
;; =================
;; Handle workspace folder operations from the File menu

(nxr/register-action! :workspace.actions/set-directory
  (fn [_state dir]
    [[:store.effects/save [:workspace-dir] dir]]))

(nxr/register-effect! :workspace.effects/load-folder-and-send-to-renderer
  (fn [{:keys [dispatch]} _ workspace-folder-path]
    (let [topics-dir (io/topics-dir-path workspace-folder-path)
          topics (topic-effects/load-all topics-dir)
          workspace-data (schema/workspace-sync-for-ipc
                          {:topics topics})]
      (dispatch [[:workspace.actions/set-directory workspace-folder-path]
                 [:ipc.effects/send-to-renderer "workspace:sync" 
                  (clj->js workspace-data)]]))))

