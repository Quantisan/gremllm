(ns gremllm.renderer.ui.settings)

;; TODO: bloaty...
(defn render-settings [encryption-available? has-api-key? api-key-input]
  [:div
   ;; Encryption warning
   (when-not encryption-available?
     [:article {:role "alert"}
      [:h4 "⚠️ Security Notice"]
      [:p "Secret encryption is not available on this system. API keys entered will only be used for this current session."]])

   ;; API Key configuration
   [:article
    [:h3 "Anthropic API Key"]
    (when has-api-key?
      [:kbd {:class "pico-color-green"} "✓ Configured"])

    [:form
     [:input {:id "anthropic-api-key"
              :name "anthropic-api-key"
              :type "password"
              :placeholder (if has-api-key?
                             "Enter new key to replace existing"
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

      (when has-api-key?
        [:button {:type "button"
                  :class "secondary outline"
                  :disabled (not encryption-available?)
                  :on {:click [[:effects/prevent-default]
                               [:settings.actions/remove-key]]}}
         "Remove Key"])]]]

   ;; Close button
   [:button {:type "button"
             :class "contrast"
             :on {:click [[:ui.actions/hide-settings]]}}
    "Close"]])

(defn render-settings-modal [open? encryption-available? has-api-key? api-key-input]
  [:dialog {:id "settings-dialog"
            :open open?}
   [:article
    [:header
     [:button {:aria-label "Close"
               :rel "prev"
               :class "close"
               :on {:click [[:ui.actions/hide-settings]]}}]
     [:h3 "⚙️ Settings"]]

    (render-settings encryption-available? has-api-key? api-key-input)]])

(defn render-api-key-warning []
  [:mark {:role "alert"}
   "🔑 API key required — "
   [:a {:href "#"
        :on {:click [[:effects/prevent-default]
                     [:ui.actions/show-settings]]}}
    "Configure in Settings"]])
