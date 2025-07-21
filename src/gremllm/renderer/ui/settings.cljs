(ns gremllm.renderer.ui.settings)

(defn render-settings [encryption-available? has-api-key?]
  [:div
   ;; Encryption status section
   (when-not encryption-available?
     [:section
      [:article {:role "alert" :class "pico-background-yellow-50"}
       [:h4 "⚠️ Security Notice"]
       [:p "Secret encryption is not available on this system. API keys will be stored in plain text."]]])

   ;; API Keys section
   [:section
    [:h3 "API Configuration"]

    ;; Anthropic API Key form
    [:article
     [:header
      [:h4 "Anthropic API Key"]
      (when has-api-key?
        [:kbd {:class "pico-color-green"} "✓ Configured"])]

     [:form
      [:label {:for "anthropic-api-key"}
       "API Key"
       [:input {:id "anthropic-api-key"
                :name "anthropic-api-key"
                :type "password"
                :placeholder (if has-api-key?
                               "Enter new key to replace existing"
                               "sk-ant-api03-...")
                :disabled (not encryption-available?)}]]

      [:div {:class "grid"}
       [:button {:type "button"
                 :disabled (not encryption-available?)}
        "Save Key"]

       (when has-api-key?
         [:button {:type "button"
                   :class "secondary outline"
                   :disabled (not encryption-available?)}
          "Remove Key"])]]]]

   ;; Footer
   [:footer
    [:button {:type "button"
              :class "contrast"
              :on {:click [[:ui.actions/hide-settings]]}}
     "Close"]]])

(defn render-settings-modal [open? encryption-available? has-api-key?]
  [:dialog {:id "settings-dialog"
            :open open?}
   [:article
    [:header
     [:button {:aria-label "Close"
               :rel "prev"
               :class "close"
               :on {:click [[:ui.actions/hide-settings]]}}]
     [:h3 "⚙️ Settings"]]

    (render-settings encryption-available? has-api-key?)]])

(defn render-api-key-warning []
  [:mark {:role "alert"}
   "🔑 API key required — "
   [:a {:href "#"
        :on {:click [[:effects/prevent-default]
                     [:ui.actions/show-settings]]}}
    "Configure in Settings"]])
