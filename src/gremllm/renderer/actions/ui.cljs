(ns gremllm.renderer.actions.ui
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.ui :as ui-state]))

(defn update-input [_state value]
  [[:form.effects/update-input value]])

;; Domain-specific effects
(nxr/register-effect! :form.effects/update-input
  (fn [_ store value]
    (swap! store assoc-in form-state/user-input-path value)))

(nxr/register-effect! :form.effects/clear-input
  (fn [_ store]
    (swap! store assoc-in form-state/user-input-path "")))

(defn submit-messages [state]
  (let [text (form-state/get-user-input state)]
    (when-not (empty? text)
      ;; TODO: IDs should use UUID, but need to ensure clj->js->clj through IPC works properly.
      ;; Probably with Malli.
      (let [assistant-id (.now js/Date)]
        [[:msg.actions/add {:id   (.now js/Date)
                            :type :user
                            :text text}]
         [:form.effects/clear-input]
         [:loading.effects/set-loading? assistant-id true]
         [:llm.effects/unset-all-errors]
         [:effects/scroll-to-bottom "chat-messages-container"]
         [:llm.effects/send-llm-messages assistant-id]]))))

;; Scroll to bottom effect for chat area
(nxr/register-effect! :effects/scroll-to-bottom
  (fn [_ _ element-id]
    (when-let [element (js/document.getElementById element-id)]
      (set! (.-scrollTop element) (.-scrollHeight element)))))

(defn show-settings [_state]
  [[:ui.effects/save ui-state/showing-settings-path true]])

(defn hide-settings [_state]
  [[:ui.effects/save ui-state/showing-settings-path false]])

;; Register UI effect
(nxr/register-effect! :ui.effects/save
  (fn [_ store path value]
    (swap! store assoc-in path value)))

