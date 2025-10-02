(ns gremllm.renderer.ui.chat
  (:require [clojure.string :as str]
            [gremllm.renderer.ui.elements :as e]))

(defn- render-user-message [message]
  [e/user-message
    [:span (:text message)]])

(defn- render-assistant-message [message]
  [e/assistant-message
    [:p (:text message)]])

(defn- render-message [message]
  (case (:type message)
    :user      (render-user-message message)
    :assistant (render-assistant-message message)
    ;; Default fallback
    [:div "Unknown message type:" (:type message)]))

(defn- render-loading-indicator []
  [e/assistant-message
    [:p {:style {:color "#666"
                 :font-style "italic"}}
     "Thinking..."]])

(defn- render-error-message [errors]
  (when-let [error (first (vals errors))]
    [:div.assistant-error "⚠️ " error]))

(defn render-chat-area [messages loading errors]
  [e/chat-area {}
   (for [message messages]
     (render-message message))
   ;; Show loading indicator if any assistant is loading
   (when (some true? (vals loading))
     (render-loading-indicator))

   ;; Show any errors
   (render-error-message errors)])

(defn- render-model-selector [selected-model has-messages?]
  (if has-messages?
    ;; Read-only: show model as static text
    [:small {:style {:color "#666"
                     :display "block"
                     :margin-bottom "0.5rem"}}
     selected-model]

    ;; Editable: show model selector dropdown
    [:label {:style {:display "block"
                     :margin-bottom "0.5rem"}}
     [:small "Model:"]
     [:select {:value selected-model
               :on {:change [[:form.actions/update-model [:event.target/value]]]}}
      [:option {:value "anthropic/claude-sonnet-4-5"} "Claude 4.5 Sonnet"]
      [:option {:value "anthropic/claude-opus-4-1-20250805"} "Claude 4.1 Opus"]
      [:option {:value "anthropic/claude-3-5-haiku-latest"} "Claude 3.5 Haiku"]]]))

(defn render-input-form [input-value selected-model has-messages? loading? has-api-key?]
  [:footer
   (render-model-selector selected-model has-messages?)
   [:form {:on {:submit [[:effects/prevent-default]
                         [:form.actions/submit]]}}
    [:fieldset {:role "group"}
     [:input {:type "text"
              :value input-value
              :placeholder (if has-api-key?
                             "Type a message..."
                             "Add API key to start chatting...")
              :on {:input [[:form.actions/update-input [:event.target/value]]]}
              :autofocus true}]

     [:button {:type "submit"
               :disabled (or loading? (not has-api-key?) (str/blank? input-value))}
      "Send"]]]])
