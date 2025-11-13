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
(nxr/register-action! :chat.actions/send-message-with-loaded-attachments chat-actions/send-message-with-loaded-attachments)
(nxr/register-action! :chat.actions/enrich-and-send chat-actions/enrich-and-send)

(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages model api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages model api-key)]])))

;; Attachment processing effects - each executes ONE domain operation then dispatches next step

(nxr/register-effect! :attachment.effects/process-batch-then-continue
  (fn [{:keys [dispatch]} _store workspace-dir file-paths messages model api-key]
    ;; Effect: process attachment batch (multiple file I/O, but conceptually one operation)
    (let [attachment-refs (attachment-effects/process-attachments-batch workspace-dir file-paths)]
      ;; CHECKPOINT 4: Attachment refs created
      (js/console.log "[CHECKPOINT 4] Main: Attachment refs created"
                      (clj->js {:attachment-refs attachment-refs
                                :refs-count (count attachment-refs)}))
      ;; Dispatch next action with results
      (dispatch [[:chat.actions/send-message-with-loaded-attachments workspace-dir attachment-refs messages model api-key]]))))

(nxr/register-effect! :attachment.effects/load-then-enrich
  (fn [{:keys [dispatch]} _store workspace-dir attachment-refs messages model api-key]
    ;; Effect: load content for each attachment (file I/O)
    (let [loaded-pairs (mapv (fn [ref]
                               [ref (attachment-effects/load-attachment-content workspace-dir (:ref ref))])
                             attachment-refs)]
      ;; CHECKPOINT 5: Attachment content loaded
      (js/console.log "[CHECKPOINT 5] Main: Attachment content loaded"
                      (clj->js {:loaded-pairs-count (count loaded-pairs)
                                :loaded-info (mapv (fn [[ref buf]]
                                                     {:ref (:ref ref)
                                                      :mime-type (:mime-type ref)
                                                      :buffer-size (when buf (.-length buf))
                                                      :buffer-exists? (some? buf)})
                                                   loaded-pairs)}))
      ;; Dispatch action with loaded data for pure transformation
      (dispatch [[:chat.actions/enrich-and-send loaded-pairs messages model api-key]]))))

;; Workspace Actions/Effects Registration
(nxr/register-action! :workspace.actions/set-directory workspace-actions/set-directory)
(nxr/register-action! :workspace.actions/open-folder workspace-actions/open-folder)
(nxr/register-action! :workspace.actions/pick-folder workspace-actions/pick-folder)
(nxr/register-action! :workspace.actions/reload workspace-actions/reload)

(nxr/register-effect! :workspace.effects/pick-folder-dialog workspace-effects/pick-folder-dialog)
(nxr/register-effect! :workspace.effects/load-and-sync workspace-effects/load-and-sync)

