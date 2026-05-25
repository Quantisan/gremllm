(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.acp :as acp-effects]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.menu :as menu]
            [gremllm.main.state :as state]
            [gremllm.schema.codec :as codec]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]))

(defn register-domain-handlers
  "Register IPC handlers for core domain operations.

   ARCHITECTURE: Hybrid pattern for IPC boundaries
   ================================================
   IPC handlers follow two patterns depending on whether they return values:

   SYNC HANDLERS (must return values to IPC caller):
   - Use direct effect pipeline (boundary adapters, not business logic)
   - Flow: Extract context → Pure transform → Execute effect → Return result
   - Examples: topic/save, topic/delete
   - Why: Electron's IPC requires synchronous return values

   ASYNC HANDLERS (fire-and-forget, return #js {}):
   - Dispatch to registered Nexus actions or effects
   - Flow: Dispatch action/effect → Return empty → Effects execute → Events notify renderer
   - Examples: acp/prompt, document/reload, document/pick
   - Actions when the handler needs state or composition; effects directly when it's a straight passthrough

   Both patterns maintain FCIS: sync handlers pipeline through pure functions;
   async handlers route through registered actions that return effect descriptions.

   Domains: Topics (save/load), Document (open/reload), ACP (agent sessions)"
  [store]
  ;; Topics - sync pattern: validate at boundary, pipeline to effect, return filepath
  (.handle ipcMain "topic/save"
           (fn [_event topic-data]
             (let [{:keys [topics-dir]} (state/get-document-paths @store)]
               (-> (js->clj topic-data :keywordize-keys true)
                   (codec/topic-from-ipc)
                   (topic-actions/topic->save-plan topics-dir)
                   (topic-effects/save-topic)))))

  (.handle ipcMain "topic/delete"
           (fn [_event topic-id]
             (let [{:keys [topics-dir]} (state/get-document-paths @store)]
               (-> topic-id
                   (codec/topic-id-from-ipc)
                   (topic-actions/topic->delete-plan topics-dir)
                   (topic-effects/delete-topic-with-confirmation)))))

  ;; Document - async pattern: dispatch to actions, results broadcast via document:opened
  (.handle ipcMain "document/reload"
           (fn [_event]
             (nxr/dispatch store {}
                           [[:document.actions/reload]])
             #js {}))

  (.handle ipcMain "document/pick"
           (fn [_event]
             (nxr/dispatch store {}
                           [[:document.effects/pick-dialog]])
             #js {}))

  ;; ACP - async pattern: dispatch to actions, response flows via IPC reply
  (.on ipcMain "acp/new-session"
       (fn [event ipc-correlation-id]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/new-session"}
                       [[:acp.effects/new-session (state/get-working-dir @store)]])))

  (.on ipcMain "acp/resume-session"
       (fn [event ipc-correlation-id acp-session-id]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/resume-session"}
                       [[:acp.effects/resume-session (state/get-working-dir @store) acp-session-id]])))

  (.on ipcMain "acp/prompt"
       (fn [event ipc-correlation-id acp-session-id message]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/prompt"}
                       [[:acp.effects/send-prompt acp-session-id (codec/user-message-from-ipc message) (state/get-active-document-path @store)]])))

  (.on ipcMain "acp/resolve-permission"
       ;; Fire-and-forget: renderer notifies main of user's accept/reject choice;
       ;; main resolves the pending Promise the SDK is awaiting.
       (fn [_event _ipc-correlation-id tool-call-id option-id]
         (nxr/dispatch store {}
                       [[:acp.effects/resolve-permission tool-call-id option-id]]))))

(defn- initialize-app [store]
  (register-domain-handlers store)
  (menu/create-menu store)
  ;; TODO: callback map couples this call site to permission ns internals —
  ;; the caller must know the subsystem's lifecycle moments to subscribe.
  (acp-effects/initialize
    {:on-session-update
     (acp-effects/make-session-update-callback store nil)
     :on-awaiting-user-decision
     (fn [enriched]
       (nxr/dispatch store {} [[:acp.events/permission-pending enriched]]))})
  (nxr/dispatch store {} [[:app.actions/set-user-data-dir (.getPath app "userData")]])
  (nxr/dispatch store {} [[:window.actions/create]]))

(defn- handle-app-activate
  "macOS: Fired when app activated (dock clicked). Creates window if none exist."
  [store]
  (when (zero? (.-length (.getAllWindows BrowserWindow)))
    (nxr/dispatch store {} [[:window.actions/create]])))

(defn- handle-window-all-closed
  "Quit on Windows/Linux when last window closes. macOS apps stay running."
  []
  (when-not (= (.-platform js/process) "darwin")
    (.quit app)))

(defn main []
  (let [store (atom {})]
    (.on app "ready" #(initialize-app store))
    (.on app "activate" #(handle-app-activate store))
    (.on app "window-all-closed" handle-window-all-closed)))
