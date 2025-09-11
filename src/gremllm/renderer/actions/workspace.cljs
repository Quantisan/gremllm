(ns gremllm.renderer.actions.workspace
  (:require [nexus.registry :as nxr]
            [gremllm.schema :as schema]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.state.topic :as topic-state]))

;; TODO: we should load previous session meta data. e.g. auto-load last opened workspace
(defn bootstrap [_state])

(defn import-workspace-topics
  "Transforms IPC workspace data into normalized form.
   Returns {:topics normalized-map :active-id first-topic-id}"
  [workspace-topics-clj]
  (let [topics (schema/workspace-from-ipc workspace-topics-clj)]
    {:topics    (or topics {})
     ;; TODO: save last active topic id so that user can continue where they left off
     :active-id (first (keys topics))}))

(defn mark-loaded
  "Mark the workspace as successfully loaded and ready for use."
  [_state]
  [[:effects/save workspace-state/loaded-path true]])

(defn opened
  "A workspace folder has been opened/loaded from disk."
  [_state workspace-data-js]
  (let [{:keys [path topics]} (js->clj workspace-data-js :keywordize-keys true)
        {:keys [topics active-id]} (import-workspace-topics topics)]

    (if (seq topics)
      [[:workspace.actions/restore-with-topics topics active-id]]
      [[:workspace.actions/initialize-empty]])))

(defn restore-with-topics
  "Restore a workspace that has existing topics."
  [_state topics active-id]
  [[:effects/save topic-state/topics-path topics]
   [:effects/save topic-state/active-topic-id-path active-id]
   [:workspace.actions/mark-loaded]])

(defn initialize-empty
  "Initialize an empty workspace with its first topic."
  [_state]
  [[:topic.actions/start-new]
   [:workspace.actions/mark-loaded]])

(defn load-error [_state error]
  (js/console.error "workspace load failed:" error)
  ;; Don't auto-create anything on load error - let user handle it
  [])

;; Effects for workspace persistence
(nxr/register-effect! :workspace.effects/load-folder
  (fn [{dispatch :dispatch} _store & [opts]]
    (dispatch
      [[:effects/promise
        {:promise    (.loadWorkspaceFolder js/window.electronAPI)
         :on-success (:on-success opts)
         :on-error   (:on-error opts)}]])))
