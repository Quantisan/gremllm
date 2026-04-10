(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.system :as system-state]
            [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.renderer.state.workspace :as workspace-state]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.ui.settings :as settings-ui]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.topics :as topics-ui]
            [gremllm.renderer.ui.welcome :as welcome-ui]
            [gremllm.renderer.ui.document :as document-ui]
            [gremllm.renderer.ui.elements :as e]))


(defn- render-workspace [state]
  ;; TODO: all these state crumbs... is there a more organized method?
  (let [has-any-api-key?      (system-state/has-any-api-key? state)
        workspace             (workspace-state/get-workspace state)
        document-content      (document-state/get-content state)
        pending-diffs         (topic-state/get-pending-diffs state)
        active-topic-id       (topic-state/get-active-topic-id state)
        topics-map            (topic-state/get-topics-map state)
        renaming-topic-id     (ui-state/renaming-topic-id state)
        nav-expanded?         (ui-state/nav-expanded? state)
        captured              (excerpt-state/get-captured state)
        anchor                (excerpt-state/get-anchor state)
        popover-pos           (excerpt-state/popover-position captured anchor)]
    [e/app-layout
     ;; Zone 1: Nav strip
     [e/nav-strip {:on {:click [[:ui.actions/toggle-nav]]}}
      [:span {:style {:font-size "1.5rem"}} "📁"]]

     ;; Zone 2: Document panel
     [e/document-panel {:on {:scroll [[:excerpt.actions/dismiss-popover]]}}
      (when nav-expanded?
        [e/nav-overlay
         (topics-ui/render-left-panel-content
           {:workspace         workspace
            :active-topic-id   active-topic-id
            :topics-map        topics-map
            :renaming-topic-id renaming-topic-id})])
      (document-ui/render-document document-content pending-diffs)
      ;; TODO: not domain obvious... perhaps rename or comment?
      (when popover-pos
        [:div {:style {:position      "absolute"
                       :top           (str (:top popover-pos) "px")
                       :left          (str (:left popover-pos) "px")
                       :z-index       5
                       :background    "var(--pico-primary)"
                       :color         "var(--pico-primary-inverse)"
                       :padding       "4px 8px"
                       :border-radius "4px"
                       :font-size     "0.85rem"}}
         "Stage"])]

     ;; Zone 3: Chat panel
     [e/chat-panel
      [e/top-bar
       (when-not has-any-api-key?
         (settings-ui/render-api-key-warning))]

      (let [messages (topic-state/get-messages state)
            awaiting-response? (and (loading-state/loading? state active-topic-id)
                                    (not= :assistant (:type (peek messages))))]
        (chat-ui/render-chat-area messages awaiting-response?))

      (chat-ui/render-input-form
        {:input-value          (form-state/get-user-input state)
         :loading?             (loading-state/loading? state active-topic-id)
         :has-any-api-key?     has-any-api-key?
         :pending-attachments  (form-state/get-pending-attachments state)
         :staged-selections    (topic-state/get-staged-selections state)})

      (settings-ui/render-settings-modal
       (merge (sensitive-state/settings-view-props state)
              {:open? (ui-state/showing-settings? state)}))]]))

(defn render-app [state]
  (if (workspace-state/loaded? state)
    (render-workspace state)
    (welcome-ui/render-welcome)))

