(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.messages :as msg-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.ui.settings :as settings-ui]))

(defn render-user-message [message]
  [:div.message-container
   [:article.user-bubble
    [:span (:text message)]]])

(defn render-assistant-message [message]
  [:div.message-container
   [:article
    [:p (:text message)]]])

(defn render-message [message]
  (case (:type message)
    :user      (render-user-message message)
    :assistant (render-assistant-message message)
    ;; Default fallback
    [:div "Unknown message type:" (:type message)]))

(defn render-loading-indicator []
  [:div.message-container
   [:article
    [:p {:style {:color "#666"
                 :font-style "italic"}}
     "Thinking..."]]])

(defn render-error-message [errors]
  (when-let [error (first (vals errors))]
    [:div.assistant-error "⚠️ " error]))

(defn render-chat-area [messages loading errors]
  [:div.chat-area {:id "chat-messages-container"
                   :style {:overflow-y "auto"
                           :flex "1"}}
   (for [message messages]
     (render-message message))
   ;; Show loading indicator if any assistant is loading
   (when (some true? (vals loading))
     (render-loading-indicator))

   ;; Show any errors
   (render-error-message errors)])

(defn render-input-form [input-value loading?]
  [:footer
   [:form
    {:on {:submit [[:effects/prevent-default]
                   [:form.actions/submit]]}}
    [:fieldset {:role "group"}
     [:input {:type "text"
              :value input-value
              :placeholder "Type a message..."
              :on {:input [[:form.actions/update-input [:event.target/value]]]}
              :autofocus true}]

     [:button {:type "submit"
               :disabled loading?} "Send"]]]])

(defn render-topic [topic]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :height "100vh"}}
   [:header
    [:h1 "Gremllm"]]
   (render-chat-area (msg-state/get-messages topic)
                     (loading-state/get-loading topic)
                     (loading-state/get-assistant-errors topic))
   (render-input-form (form-state/get-user-input topic) (loading-state/loading? topic))

   (settings-ui/render-settings-modal (ui-state/showing-settings? topic))])
