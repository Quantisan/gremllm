(ns gremllm.renderer.actions.ui
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]))

(defn update-input [_state value]
  [[:effects/save form-state/user-input-path value]])

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
         [:effects/save form-state/user-input-path ""] ;; TODO: this should be :form.actions/clear-input
         [:ui.actions/focus-chat-input]
         [:loading.actions/set-loading? assistant-id true]
         [:llm.actions/unset-all-errors]
         [:ui.actions/scroll-chat-to-bottom]
         [:llm.effects/send-llm-messages assistant-id model]]))))

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

(defn show-settings [_state]
  ;; Refresh system info to ensure settings modal displays current API key status
  [[:system.actions/request-info]
   [:effects/save ui-state/showing-settings-path true]])

(defn hide-settings [_state]
  ;; Refresh system info to ensure has-any-api-key? is up-to-date
  [[:system.actions/request-info]
   [:effects/save ui-state/showing-settings-path false]])

