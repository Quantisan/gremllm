(ns gremllm.renderer.ui.settings
  (:require [gremllm.renderer.ui.elements :as e]))

;; TODO: bloaty...
(defn render-settings [encryption-available? api-key-input redacted-api-key]
  [:div
   ;; Encryption warning
   (when-not encryption-available?
     [e/alert
      [:h4 "⚠️ Security Notice"]
      [:p "Secret encryption is not available on this system. API keys entered will only be used for this current session."]])

   ;; API Key configuration
   [:article
    [:h3 "Anthropic API Key"]
    (when redacted-api-key
      [:kbd {:class "pico-color-green"} "✓ Configured"])

    [:form
     [:input {:id "anthropic-api-key"
              :name "anthropic-api-key"
              :type "password"
              :placeholder (if redacted-api-key
                             (str "Current: •••••••" redacted-api-key)
                             "sk-ant-api03-...")
              :disabled (not encryption-available?)
              :value api-key-input
              :on {:input [[:settings.actions/update-api-key-input [:event.target/value]]]}}]

     [:div {:class "grid"}
      [:button {:type "button"
                :disabled (not encryption-available?)
                :on {:click [[:effects/prevent-default]
                             [:settings.actions/save-key]]}}
       "Save Key"]

      (when redacted-api-key
        [:button {:type "button"
                  :class ["secondary" "outline"]
                  :disabled (not encryption-available?)
                  :on {:click [[:effects/prevent-default]
                               [:settings.actions/remove-key]]}}
         "Remove Key"])]]]

   ;; Close button
   [:button {:type "button"
             :class "contrast"
             :on {:click [[:ui.actions/hide-settings]]}}
    "Close"]])

(defn render-settings-modal [{:keys [open? encryption-available? api-key-input redacted-api-key]}]
  [e/modal {:open? open?
            :on-close [[:ui.actions/hide-settings]]}
   (render-settings encryption-available? api-key-input redacted-api-key)])

(defn render-api-key-warning []
  [:mark {:role "alert"}
   "🔑 API key required — "
   [:a {:href "#"
        :on {:click [[:effects/prevent-default]
                     [:ui.actions/show-settings]]}}
    "Configure in Settings"]])
