(ns gremllm.renderer.actions.ui
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]))

(defn update-input [_state value]
  [[:form.effects/update-input value]])

(defn handle-submit-keys [_state {:keys [key shift?]}]
  (when (and (= key "Enter") (not shift?))
    [[:effects/prevent-default]
     [:form.actions/submit]]))

;; Domain-specific effects
(nxr/register-effect! :form.effects/update-input
  (fn [_ store value]
    (swap! store assoc-in form-state/user-input-path value)))

(nxr/register-effect! :form.effects/clear-input
  (fn [_ store]
    (swap! store assoc-in form-state/user-input-path "")))

(defn submit-messages [state]
  (let [text (form-state/get-user-input state)
        model (:model (topic-state/get-active-topic state))]
    (when-not (empty? text)
      ;; TODO: IDs should use UUID, but need to ensure clj->js->clj through IPC works properly.
      ;; Probably with Malli.
      (let [assistant-id (.now js/Date)]
        [[:messages.actions/add-to-chat {:id   (.now js/Date)
                                         :type :user
                                         :text text}]
         [:form.effects/clear-input]
         [:ui.actions/focus-chat-input]
         [:loading.actions/set-loading? assistant-id true]
         [:llm.actions/unset-all-errors]
         [:ui.actions/scroll-chat-to-bottom]
         [:llm.effects/send-llm-messages assistant-id model]]))))

;; Reusable DOM placeholders
(nxr/register-placeholder! :dom/element-by-id
  (fn [_ id]
    (js/document.getElementById id)))

(nxr/register-placeholder! :dom.element/property
  (fn [_ element prop]
    (when element (aget element prop))))

;; Generic DOM effect
(nxr/register-effect! :effects/set-element-property
  (fn [_ _ {:keys [on-element set-property to-value]}]
    (when (and on-element to-value)
      (aset on-element set-property to-value))))

;; Pure action for scrolling chat to bottom
(defn scroll-chat-to-bottom [_state]
  (let [element-id "chat-messages-container"]
    [[:effects/set-element-property
      {:on-element   [:dom/element-by-id element-id]
       :set-property "scrollTop"
       :to-value     [:dom.element/property [:dom/element-by-id element-id] "scrollHeight"]}]]))

;; Pure action for focusing chat input
(defn focus-chat-input [_state]
  [[:effects/focus ".chat-input"]])

;; Focus element by selector
(nxr/register-effect! :effects/focus
  (fn [_ _ selector]
    (when-let [element (js/document.querySelector selector)]
      (.focus element))))

(defn show-settings [_state]
  ;; Refresh system info to ensure settings modal displays current API key status
  [[:system.actions/request-info]
   [:ui.effects/save ui-state/showing-settings-path true]])

(defn hide-settings [_state]
  ;; Refresh system info to ensure has-any-api-key? is up-to-date
  [[:system.actions/request-info]
   [:ui.effects/save ui-state/showing-settings-path false]])

;; Register UI effect
(nxr/register-effect! :ui.effects/save
  (fn [_ store path value]
    (swap! store assoc-in path value)))

