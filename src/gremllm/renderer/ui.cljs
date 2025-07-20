(ns gremllm.renderer.ui
  (:require [gremllm.renderer.state.messages :as msg-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.ui :as ui-state]))

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

(defn render-settings [encryption-available?]
  [:div
   [:h2 "Settings"]
   [:section
    [:h3 "API Keys"]
    (if-not encryption-available?
      [:div "⚠️ Secrets cannot be encrypted on this system"]
      [:p "API key storage is available."])]
   [:div {:style {:margin-top "2rem"}}
    [:button
     {:on {:click [[:ui.actions/hide-settings]]}}
     "Done"]]])

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

   ;; Modal overlay when showing settings
   (when (ui-state/showing-settings? topic)
     (println "DEBUG: showing settings")
     [:<>
      ;; Backdrop - click to close
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0, 0, 0, 0.5)"
                     :z-index 1000}
             :on {:click [[:ui.actions/hide-settings]]}}]
      ;; Modal content
      [:div {:style {:position "fixed"
                     :top "50%" :left "50%"
                     :transform "translate(-50%, -50%)"
                     :background "white"
                     :padding "2rem"
                     :border-radius "8px"
                     :z-index 1001
                     :max-width "600px"
                     :width "90%"
                     :max-height "80vh"
                     :overflow-y "auto"}}
       (render-settings false)]])])  ;; hardcode false for now
