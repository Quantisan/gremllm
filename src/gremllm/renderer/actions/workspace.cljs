(ns gremllm.renderer.actions.workspace
  (:require [nexus.registry :as nxr]
            [gremllm.schema :as schema]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn bootstrap [_state]
  [[:workspace.effects/load-folder {:on-success [[:workspace.actions/populate-topics]]
                                    :on-error   [[:workspace.actions/load-error]]}]])

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

(defn populate-topics [_state workspace-topics-js]
  (let [workspace-topics-clj (js->clj workspace-topics-js :keywordize-keys true)
        {:keys [topics active-id]} (import-workspace-topics workspace-topics-clj)]

    (if (seq topics)
      [[:effects/save topic-state/topics-path topics]
       [:effects/save topic-state/active-topic-id-path active-id]
       [:workspace.actions/mark-loaded]]

      [[:topic.actions/start-new]
       [:workspace.actions/mark-loaded]])))

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
