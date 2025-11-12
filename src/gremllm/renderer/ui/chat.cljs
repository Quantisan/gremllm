(ns gremllm.renderer.ui.chat
  (:require [clojure.string :as str]
            [gremllm.renderer.ui.elements :as e]
            [gremllm.schema :as schema]
            ["snarkdown" :as snarkdown]
            ["sanitize-html" :as sanitize-html]))

(defn markdown->html [text]
  (-> text
      snarkdown
      (sanitize-html #js {:allowedTags #js ["h1" "h2" "h3" "h4" "h5" "h6"
                                            "p" "ul" "ol" "li"
                                            "code" "pre" "em" "strong" "a" "br"
                                            "blockquote" "hr"]
                          :allowedAttributes #js {:a #js ["href" "title"]
                                                  :code #js ["class"]}
                          :allowedSchemes #js ["http" "https" "mailto"]})))

(defn- render-user-message [message]
  [e/user-message
    [:span (:text message)]])

(defn- render-assistant-message [message]
  [e/assistant-message
    [:div {:innerHTML (markdown->html (:text message))}]])

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
     [:span {:style {:display "inline-block"
                     :animation "spin 1s linear infinite"
                     :margin-right "0.5rem"}}
      "⠿"]
     "Computing..."]])

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

(defn- render-attachment-indicator [pending-attachments]
  (when (seq pending-attachments)
    [:div {:style {:margin-bottom "0.5rem"
                   :font-size "0.875rem"
                   :color "#666"}}
     [:span "📎 " (count pending-attachments) " file" (when (> (count pending-attachments) 1) "s") " attached"]
     [:button {:type "button"
               :style {:margin-left "0.5rem"
                       :padding "0.125rem 0.5rem"
                       :font-size "0.75rem"}
               :on {:click [[:ui.actions/clear-pending-attachments]]}}
      "Clear"]]))

(defn- render-model-selector [selected-model has-messages?]
  (if has-messages?
    ;; Read-only: show model as static text
    [:small {:style {:color "#666"
                     :display "block"
                     :margin-bottom "0.5rem"}}
     (get schema/supported-models selected-model selected-model)]

    ;; Editable: show model selector dropdown
    [:label {:style {:display "block"
                     :margin-bottom "0.5rem"}}
     [:small "Model:"]
     [:select {:value selected-model ;; FIX: this doesn't seem to work. UI defaults to the first item on list instead of selected-model
               :on {:change [[:topic.actions/update-model [:event.target/value]]]}}
      (for [[provider-name model-ids] (schema/models-by-provider)]
        [:optgroup {:label provider-name}
         (for [model-id model-ids
               :let [display-name (get schema/supported-models model-id)]]
           [:option {:value model-id} display-name])])]]))

(defn render-input-form [{:keys [input-value selected-model has-messages? loading? has-any-api-key? pending-attachments]}]
  [:footer
   [:form {:on {:submit [[:effects/prevent-default]
                         [:form.actions/submit]]}}
    (render-model-selector selected-model has-messages?)
    (render-attachment-indicator pending-attachments)
    [:fieldset {:role "group"}
     [:textarea {:class "chat-input"
                 :rows 2
                 :value input-value
                 :placeholder (if has-any-api-key?
                                "Type a message... (Shift+Enter for new line)"
                                "Add API key to start chatting...")
                 :on {:input [[:form.actions/update-input [:event.target/value]]]
                      :keydown [[:form.actions/handle-submit-keys [:event/key-pressed]]]
                      :dragover [[:form.actions/handle-dragover]]
                      :drop [[:effects/prevent-default]
                             [:form.actions/handle-file-drop [:event/dropped-files]]]}
                 :autofocus true}]

     [:button {:type "submit"
               :disabled (or loading? (not has-any-api-key?) (str/blank? input-value))}
      "Send"]]]])
