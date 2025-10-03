(ns gremllm.renderer.actions.workspace
  (:require [gremllm.schema :as schema]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.state.topic :as topic-state]))

;; TODO: we should load previous session meta data. e.g. auto-load last opened workspace
(defn bootstrap [_state])

(defn mark-loaded
  "Mark the workspace as successfully loaded and ready for use."
  [_state]
  [[:effects/save workspace-state/loaded-path true]])

(defn set-workspace
  "Save workspace metadata into renderer state."
  [_state workspace]
  [[:effects/save workspace-state/workspace-path workspace]])

(defn opened
  "A workspace folder has been opened/loaded from disk."
  [_state workspace-data-js]
  (let [{topics    :topics
         workspace :workspace} (schema/workspace-sync-from-ipc workspace-data-js)
        ;; TODO: save last active topic id so that user can continue where they left off
        active-topic-id (first (keys topics))]

    (if (empty? topics)
      [[:workspace.actions/set workspace]
       [:workspace.actions/initialize-empty]]

      [[:workspace.actions/set workspace]
       [:workspace.actions/restore-with-topics
        {:topics          topics
         :active-topic-id active-topic-id}]])))

(defn restore-with-topics
  "Restore a workspace that has existing topics."
  [_state {:keys [topics active-topic-id]}]
  (let [active-topic (get topics active-topic-id)
        model        (:model active-topic)]
    [[:effects/save topic-state/topics-path topics]
     [:topic.actions/set-active active-topic-id model]
     [:workspace.actions/mark-loaded]]))

(defn initialize-empty
  "Initialize an empty workspace with its first topic."
  [_state]
  [[:topic.actions/start-new]
   [:workspace.actions/mark-loaded]])

(defn load-error [_state error]
  (js/console.error "workspace load failed:" error)
  ;; Don't auto-create anything on load error - let user handle it
  [])

(defn pick-folder
  "User wants to pick and open a different workspace folder."
  [_state]
  [[:effects/promise
    {:promise (.pickWorkspaceFolder js/window.electronAPI)}]])
     ;; No handlers needed - workspace data arrives via workspace:sync IPC event

