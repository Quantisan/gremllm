(ns gremllm.renderer.ui.settings
  (:require [gremllm.renderer.ui.elements :as e]
            [gremllm.schema :as schema]))

(defn- render-encryption-warning [{:keys [encryption-available?]}]
  (when-not encryption-available?
    [e/alert
     [:h4 "⚠️ Security Notice"]
     [:p "Secret encryption is not available on this system. API keys entered will only be used for this current session."]]))

(defn- provider-placeholder
  "Returns example API key format for each provider."
  [provider]
  (case provider
    :anthropic "sk-ant-api03-..."
    :openai    "sk-proj-..."
    :google    "AIza..."))

(defn- render-api-key-input [{:keys [encryption-available? input-value redacted-key provider provider-name]}]
  (let [placeholder (if redacted-key
                      (str "Current: •••••••" redacted-key)
                      (provider-placeholder provider))
        input-id (str (name provider) "-api-key")]
    [:input {:id input-id
             :name input-id
             :type "password"
             :placeholder placeholder
             :disabled (not encryption-available?)
             :value (or input-value "")
             :on {:input [[:settings.actions/update-input provider [:event.target/value]]]}}]))

(defn- render-save-button [{:keys [encryption-available? provider]}]
  [:button {:type "button"
            :disabled (not encryption-available?)
            :on {:click [[:effects/prevent-default]
                         [:settings.actions/save-key provider]]}}
   "Save Key"])

(defn- render-remove-button [{:keys [encryption-available? provider]}]
  [:button {:type "button"
            :class ["secondary" "outline"]
            :disabled (not encryption-available?)
            :on {:click [[:effects/prevent-default]
                         [:settings.actions/remove-key provider]]}}
   "Remove Key"])

(defn- render-provider-section [props]
  (let [{:keys [provider-name redacted-key]} props]
    [:article
     [:h3 (str provider-name " API Key")]
     (when redacted-key
       [:kbd {:class "pico-color-green"} "✓ Configured"])
     [:form
      [:div {:class "grid" :style {:grid-template-columns "1fr auto"}}
       (render-api-key-input props)
       (render-save-button props)]
      (when redacted-key
        (render-remove-button props))]]))

(defn render-settings [props]
  [:div
   (render-encryption-warning props)

   ;; Render a section for each provider
   (for [provider schema/supported-providers]
     ^{:key provider}
     (render-provider-section
       (merge props
              {:provider provider
               :provider-name (schema/provider-display-name provider)
               :redacted-key (get-in props [:api-keys provider])
               :input-value (get-in props [:api-key-inputs provider])})))

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
