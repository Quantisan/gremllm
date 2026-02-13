(ns gremllm.main.core
  (:require [gremllm.main.actions]
            [gremllm.main.actions.secrets :as secrets]
            [gremllm.main.actions.document :as document-actions]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.effects.acp :as acp-effects]
            [gremllm.main.effects.workspace :as workspace-effects]
            [gremllm.main.menu :as menu]
            [gremllm.main.io :as io]
            [gremllm.main.state :as state]
            [gremllm.schema.codec :as codec]
            [nexus.registry :as nxr]
            ["electron/main" :refer [app BrowserWindow ipcMain]]))

(defn system-info [secrets encryption-available?]
  {:encryption-available? encryption-available?
   :secrets               (secrets/redact-all-string-values secrets)})

(defn register-domain-handlers
  "Register IPC handlers for core domain operations.

   ARCHITECTURE: Hybrid pattern for IPC boundaries
   ================================================
   IPC handlers follow two patterns depending on whether they return values:

   SYNC HANDLERS (must return values to IPC caller):
   - Use direct effect pipeline (boundary adapters, not business logic)
   - Flow: Extract context → Pure transform → Execute effect → Return result
   - Examples: topic/save, topic/delete, secrets/*, system/get-info
   - Why: Electron's IPC requires synchronous return values

   ASYNC HANDLERS (fire-and-forget, return #js {}):
   - Dispatch to registered Nexus actions (not effects directly)
   - Flow: Dispatch action → Return empty → Effects execute → Events notify renderer
   - Examples: acp/prompt, workspace/reload, workspace/pick-folder
   - Why: Action registry provides discoverability and instrumentation points

   Both patterns maintain FCIS: sync handlers pipeline through pure functions;
   async handlers route through registered actions that return effect descriptions.

   Domains: Topics (save/load), Workspace (bulk ops), Secrets (config),
            System (capabilities), ACP (agent sessions)"
  [store secrets-filepath]
  ;; Topics - sync pattern: validate at boundary, pipeline to effect, return filepath
  (.handle ipcMain "topic/save"
           (fn [_event topic-data]
             (let [workspace-dir (state/get-workspace-dir @store)]
               (-> (js->clj topic-data :keywordize-keys true)
                   (codec/topic-from-ipc)
                   (topic-actions/topic->save-plan (io/topics-dir-path workspace-dir))
                   (workspace-effects/save-topic)))))

  (.handle ipcMain "topic/delete"
           (fn [_event topic-id]
             (let [workspace-dir (state/get-workspace-dir @store)
                   topics-dir    (io/topics-dir-path workspace-dir)]
               (-> topic-id
                   (codec/topic-id-from-ipc)
                   (topic-actions/topic->delete-plan topics-dir)
                   (workspace-effects/delete-topic-with-confirmation)))))

  ;; Document - sync pattern: create new document file and return content
  (.handle ipcMain "document/create"
           (fn [_event]
             (let [workspace-dir (state/get-workspace-dir @store)]
               (-> (document-actions/create-plan workspace-dir)
                   (workspace-effects/create-document)
                   (clj->js)))))

  ;; Workspace - async pattern: dispatch to actions, results broadcast via workspace:opened
  (.handle ipcMain "workspace/reload"
           (fn [_event]
             (nxr/dispatch store {}
                           [[:workspace.actions/reload]])
             #js {}))

  (.handle ipcMain "workspace/pick-folder"
           (fn [_event]
             ;; Reuse the existing workspace action - it already handles the full flow
             (nxr/dispatch store {}
                           [[:workspace.actions/pick-folder]])
             ;; Return empty - workspace data flows via workspace:opened
             #js {}))

  ;; Secrets - sync pattern: call functions directly at boundary, return results
  (.handle ipcMain "secrets/save"
           (fn [_event key value]
             (secrets/save secrets-filepath (keyword key) value)))

  (.handle ipcMain "secrets/delete"
           (fn [_event key]
             (secrets/del secrets-filepath (keyword key))))

  ;; System - sync pattern: gather info, transform to IPC format, return
  (.handle ipcMain "system/get-info"
           (fn [_event]
             (-> (system-info
                   (secrets/load-all secrets-filepath)
                   (secrets/check-availability))
                 (codec/system-info-to-ipc)
                 (clj->js))))

  ;; ACP - async pattern: dispatch to actions, response flows via IPC reply
  (.on ipcMain "acp/new-session"
       (fn [event ipc-correlation-id]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/new-session"}
                       [[:acp.effects/new-session (state/get-workspace-dir @store)]])))

  (.on ipcMain "acp/resume-session"
       (fn [event ipc-correlation-id acp-session-id]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/resume-session"}
                       [[:acp.effects/resume-session (state/get-workspace-dir @store) acp-session-id]])))

  (.on ipcMain "acp/prompt"
       ;; TODO: we should pass the document path from Renderer to here
       (fn [event ipc-correlation-id acp-session-id text]
         (nxr/dispatch store {:ipc-event event
                              :ipc-correlation-id ipc-correlation-id
                              :channel "acp/prompt"}
                       [[:acp.effects/send-prompt
                         acp-session-id text
                         (state/get-workspace-dir @store)]]))))

(defn- setup-system-resources [store]
  (let [user-data-dir   (.getPath app "userData")
        secrets-filepath (io/secrets-file-path user-data-dir)]
    (register-domain-handlers store secrets-filepath)
    (menu/create-menu store)
    ;; Wire ACP dispatcher bridge
    ;; WATCH-OUT: event-type from JS is converted to keyword without validation.
    ;; If JS dispatches unexpected event types, they become unregistered actions
    ;; (silently ignored by Nexus). Add a whitelist if this causes debugging pain.
    (acp-effects/set-dispatcher!
      (fn [event-type data]
        (let [coerced (codec/acp-session-update-from-js data)]
          (nxr/dispatch store {} [[(keyword event-type) coerced]]))))))

(defn- initialize-app [store]
  (setup-system-resources store)
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
