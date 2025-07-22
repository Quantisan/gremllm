(ns gremllm.renderer.actions.settings
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.settings :as settings-state]))

;; Actions for API key management

(defn update-api-key-input [_state value]
  [[:settings.effects/save-input value]])

(defn save-api-key [state]
  (let [api-key (settings-state/get-api-key-input state)]
    (if (empty? api-key)
      [] ; No-op if empty
      [[:effects/promise
        {:promise    (.saveSecret js/window.electronAPI "anthropic-api-key" api-key)
         :on-success [:settings.actions/save-success]
         :on-error   [:settings.actions/save-error]}]])))

(defn remove-api-key [_state]
  [[:effects/promise
    {:promise    (.deleteSecret js/window.electronAPI "anthropic-api-key")
     :on-success [:settings.actions/remove-success]
     :on-error   [:settings.actions/remove-error]}]])

;; Success/error handlers
(defn save-success [_state _result]
  (println "API key saved successfully!")
  [[:settings.effects/clear-input]
   [:ui.actions/hide-settings]])

(defn save-error [_state error]
  (println "Failed to save API key:" error)
  []) ; TODO: Show error to user

(defn remove-success [_state _result]
  (println "API key removed successfully!")
  [[:ui.actions/hide-settings]])

(defn remove-error [_state error]
  (println "Failed to remove API key:" error)
  []) ; TODO: Show error to user

;; Register effects
(nxr/register-effect! :settings.effects/save-input
  (fn [_ store value]
    (swap! store assoc-in settings-state/api-key-input-path value)))

(nxr/register-effect! :settings.effects/clear-input
  (fn [_ store]
    (swap! store assoc-in settings-state/api-key-input-path "")))
