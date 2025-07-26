(ns gremllm.renderer.ui.settings
  (:require [gremllm.renderer.ui.elements :as e]))

(defn- render-encryption-warning [{:keys [encryption-available?]}]
  (when-not encryption-available?
    [e/alert
     [:h4 "⚠️ Security Notice"]
     [:p "Secret encryption is not available on this system. API keys entered will only be used for this current session."]]))

(defn- render-api-key-input [{:keys [encryption-available? api-key-input redacted-api-key]}]
  (let [placeholder (if redacted-api-key
                      (str "Current: •••••••" redacted-api-key)
                      "sk-ant-api03-...")]
    [:input {:id "anthropic-api-key"
             :name "anthropic-api-key"
             :type "password"
             :placeholder placeholder
             :disabled (not encryption-available?)
             :value api-key-input
             :on {:input [[:settings.actions/update-api-key-input [:event.target/value]]]}}]))

(defn- render-api-key-actions [{:keys [encryption-available? redacted-api-key]}]
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
      "Remove Key"])])

(defn- render-api-key-section [props]
  [:article
   [:h3 "Anthropic API Key"]
   (when (:redacted-api-key props)
     [:kbd {:class "pico-color-green"} "✓ Configured"])
   [:form
    (render-api-key-input props)
    (render-api-key-actions props)]])

(defn render-settings [props]
  [:div
   (render-encryption-warning props)
   (render-api-key-section props)

   ;; Close button
   [:button {:type "button"
             :class "contrast"
             :on {:click [[:ui.actions/hide-settings]]}}
    "Close"]])

(defn render-settings-modal [{:keys [open?] :as props}]
  [e/modal {:open? open?
            :on-close [[:ui.actions/hide-settings]]}
   (render-settings props)])

(defn render-api-key-warning []
  [:mark {:role "alert"}
   "🔑 API key required — "
   [:a {:href "#"
        :on {:click [[:effects/prevent-default]
                     [:ui.actions/show-settings]]}}
    "Configure in Settings"]])
