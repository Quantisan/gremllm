(ns gremllm.renderer.ui.settings
  (:require [gremllm.renderer.ui.elements :as e]
            [gremllm.schema :as schema]))

(defn- maybe-render-encryption-warning [{:keys [encryption-available?]}]
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

(defn- render-api-key-input [{:keys [encryption-available? input-value redacted-key provider _provider-name]}]
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

(defn- provider-section-props
  "Extracts provider-specific props from global state."
  [props provider]
  {:encryption-available? (:encryption-available? props)
   :provider provider
   :provider-name (schema/provider-display-name provider)
   :redacted-key (get-in props [:api-keys provider])
   :input-value (get-in props [:api-key-inputs provider])})

(defn render-settings [{:keys [_encryption-available? _api-keys _api-key-inputs] :as props}]
  [:div
   (maybe-render-encryption-warning props)
   (for [provider schema/supported-providers]
     ^{:key provider}
     (render-provider-section (provider-section-props props provider)))
   [:button {:type "button"
             :class "contrast"
             :on {:click [[:ui.actions/hide-settings]]}}
    "Close"]])

(defn render-settings-modal [{:keys [open? _encryption-available? _api-keys _api-key-inputs] :as props}]
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
