(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.ui.chat :as chat-ui]
            [gremllm.renderer.ui.welcome :as welcome-ui]
            [gremllm.renderer.ui.document :as document-ui]
            [gremllm.renderer.ui.elements :as e]))

(defn- render-app-layout [state]
  (let [document-content      (document-state/get-content state)
        pending-diffs         (topic-state/get-pending-diffs state)
        active-topic-id       (topic-state/get-active-topic-id state)
        captured              (excerpt-state/get-captured state)
        anchor                (excerpt-state/get-anchor state)
        popover-pos           (excerpt-state/popover-position captured anchor)
        excerpts              (topic-state/get-excerpts state)]
    [e/app-layout
     ;; Zone 1: Document panel
     [e/document-panel {:on {:scroll    [[:excerpt.actions/dismiss-popover]]
                             :mousedown [[:excerpt.actions/dismiss-popover]]}}
      (document-ui/render-document document-content pending-diffs excerpts)
      (when popover-pos
        [:button {:style {:position      "absolute"
                          :top           (str (:top popover-pos) "px")
                          :left          (str (:left popover-pos) "px")
                          :z-index       5
                          :background    "var(--pico-primary)"
                          :color         "var(--pico-primary-inverse)"
                          :padding       "4px 8px"
                          :border-radius "4px"
                          :font-size     "0.85rem"
                          :border        "none"
                          :cursor        "pointer"}
                  :on {:mousedown [[:effects/stop-propagation]]
                       :click     [[:excerpt.actions/add]]}}
         "Add excerpt"])]

     ;; Zone 2: Chat panel
     [e/chat-panel
      (let [messages (topic-state/get-messages state)
            awaiting-response? (and (loading-state/loading? state active-topic-id)
                                    (= :user (:type (peek messages))))]
        (chat-ui/render-chat-area messages awaiting-response?))

      (chat-ui/render-input-form
        {:input-value          (form-state/get-user-input state)
         :loading?             (loading-state/loading? state active-topic-id)
         :pending-attachments  (form-state/get-pending-attachments state)
         :excerpts             excerpts})]]))

(defn render-app [state]
  (if (document-state/loaded? state)
    (render-app-layout state)
    (welcome-ui/render-welcome)))
