(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.system :as system-state]
            [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.ui.settings :as settings-ui]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.topics :as topics-ui]
            [gremllm.renderer.ui.welcome :as welcome-ui]
            [gremllm.renderer.ui.elements :as e]))


(defn- render-workspace [state]
  (let [has-api-key?          (system-state/has-anthropic-api-key? state)
        workspace             (workspace-state/get-workspace state)
        active-topic-id       (topic-state/get-active-topic-id state)
        topics-map            (topic-state/get-topics-map state)
        renaming-topic-id     (ui-state/renaming-topic-id state)]
    [e/app-layout
     [e/left-panel
      (topics-ui/render-left-panel-content
        {:workspace             workspace
         :active-topic-id       active-topic-id
         :topics-map            topics-map
         :renaming-topic-id     renaming-topic-id})]

     [e/main-panel
      [e/top-bar
       (when-not has-api-key?
         (settings-ui/render-api-key-warning))]

      ;; TODO: we have `topics-map` already. Why are we getting from state below? What's more
      ;; readable and simple?
      (chat-ui/render-chat-area (topic-state/get-messages state)
                                (loading-state/get-loading state)
                                (loading-state/get-assistant-errors state))

      (chat-ui/render-input-form
        {:input-value     (form-state/get-user-input state)
         :selected-model  (form-state/get-selected-model state)
         :has-messages?   (seq (topic-state/get-messages state))
         :loading?        (loading-state/loading? state)
         :has-api-key?    has-api-key?})

      (settings-ui/render-settings-modal
       {:open? (ui-state/showing-settings? state)
        :encryption-available? (system-state/encryption-available? state)
        :api-key-input (sensitive-state/get-api-key-input state)
        :redacted-api-key (system-state/get-redacted-anthropic-api-key state)})]]))

(defn render-app [state]
  (if (workspace-state/loaded? state)
    (render-workspace state)
    (welcome-ui/render-welcome)))

