(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.system :as system-state]
            [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.renderer.ui.settings :as settings-ui]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.elements :as e]))


(defn render-app [state]
  (let [has-api-key?          (system-state/has-anthropic-api-key? state)
        workspace-name        "Kaitenzushi Corp Acquisition"
        workspace-description "Analyzing Japanese conveyor belt sushi chain for potential acquisition."]
    [e/app-layout
     [e/left-panel
      [:nav
       [:ul
        [:li [:a {:href     "#"
                  :on-click [[:effects/prevent-default] [:topic.actions/start-new-topic]]}
              "➕ New Topic"]]]]
      [:hr]
      [:hgroup
       [:h4 workspace-name]
       [:p [:small workspace-description]]]
      [:nav
       [:ul
        [:li [:a {:href "#"} "✓ Market Viability Assessment"]]
        [:li [:a {:href "#"} "~ Target Due Diligence"]
         [:ul
          [:li [:a {:href "#"} "~ Tourist-Centric Strategy"]]]]]]]

     [e/main-panel
      [e/top-bar
       (when-not has-api-key?
         (settings-ui/render-api-key-warning))]

      (chat-ui/render-chat-area (topic-state/get-messages state)
                                (loading-state/get-loading state)
                                (loading-state/get-assistant-errors state))

      (chat-ui/render-input-form (form-state/get-user-input state)
                                 (loading-state/loading? state)
                                 has-api-key?)

      (settings-ui/render-settings-modal
       {:open? (ui-state/showing-settings? state)
        :encryption-available? (system-state/encryption-available? state)
        :api-key-input (sensitive-state/get-api-key-input state)
        :redacted-api-key (system-state/get-redacted-anthropic-api-key state)})]]))
