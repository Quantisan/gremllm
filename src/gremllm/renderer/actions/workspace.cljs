(ns gremllm.renderer.actions.workspace
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.state.topic :as topic-state]))

(defn bootstrap [_state]
  [[:workspace.effects/load-folder {:on-success [[:workspace.actions/populate-topics]]
                                    :on-error   [[:workspace.actions/load-error]]}]])

;; TODO: review to refactor
(defn populate-topics [_state workspace-topics-js]
  (let [workspace-topics (js->clj workspace-topics-js :keywordize-keys true)
        topics (or workspace-topics {})
        normalized-topics (reduce-kv (fn [m k v]
                                       (assoc m k (topic/normalize-topic v)))
                                     {}
                                     topics)
        ;; Hardcoded for now - will be replaced with actual last-active logic
        last-active-id "topic-xxx"
        active-topic-id (if (get normalized-topics last-active-id)
                         last-active-id
                         (first (keys normalized-topics)))]
    (if (seq normalized-topics)
      [[:effects/save topic-state/topics-path normalized-topics]
       [:effects/save topic-state/active-topic-id-path active-topic-id]]

      [[:topic.actions/start-new]])))

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
