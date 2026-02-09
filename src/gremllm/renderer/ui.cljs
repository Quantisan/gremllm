(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.system :as system-state]
            [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.state.acp :as acp-state]
            [gremllm.renderer.ui.settings :as settings-ui]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.topics :as topics-ui]
            [gremllm.renderer.ui.welcome :as welcome-ui]
            [gremllm.renderer.ui.document :as document-ui]
            [gremllm.renderer.ui.elements :as e]))


(defn- render-workspace [state]
  (let [has-any-api-key?      (system-state/has-any-api-key? state)
        workspace             (workspace-state/get-workspace state)
        active-topic-id       (topic-state/get-active-topic-id state)
        topics-map            (topic-state/get-topics-map state)
        renaming-topic-id     (ui-state/renaming-topic-id state)
        nav-expanded?         (ui-state/nav-expanded? state)]
    [e/app-layout
     ;; Zone 1: Nav strip
     [e/nav-strip {:on {:click [[:ui.actions/toggle-nav]]}}
      [:span {:style {:font-size "1.5rem"}} "📁"]]

     ;; Zone 2: Document panel
     [e/document-panel
      (when nav-expanded?
        [e/nav-overlay
         (topics-ui/render-left-panel-content
           {:workspace         workspace
            :active-topic-id   active-topic-id
            :topics-map        topics-map
            :renaming-topic-id renaming-topic-id})])
      (document-ui/render-document-stub)]

     ;; Zone 3: Chat panel
     [e/chat-panel
      [e/top-bar
       (when-not has-any-api-key?
         (settings-ui/render-api-key-warning))]

      (chat-ui/render-chat-area (topic-state/get-messages state)
                                (acp-state/loading? state))

      (chat-ui/render-input-form
        {:input-value          (form-state/get-user-input state)
         :loading?             (loading-state/loading? state)
         :has-any-api-key?     has-any-api-key?
         :pending-attachments  (form-state/get-pending-attachments state)})

      (settings-ui/render-settings-modal
       (merge (sensitive-state/settings-view-props state)
              {:open? (ui-state/showing-settings? state)}))]]))

(defn render-app [state]
  (if (workspace-state/loaded? state)
    (render-workspace state)
    (welcome-ui/render-welcome)))

