(ns gremllm.renderer.actions.settings
  (:require [gremllm.renderer.state.sensitive :as sensitive-state]))

;; Constants
(def api-key-name "anthropic-api-key")

;; Actions for API key management

(defn update-api-key-input [_state value]
  [[:effects/save sensitive-state/api-key-input-path value]])

(defn save-key [state]
  (let [api-key (sensitive-state/get-api-key-input state)]
    (if (empty? api-key)
      [] ; No-op if empty
      [[:effects/promise
        {:promise    (.saveSecret js/window.electronAPI api-key-name api-key)
         :on-success [[:settings.actions/save-success]]
         :on-error   [[:settings.actions/save-error]]}]])))

(defn remove-key [_state]
  [[:effects/promise
    {:promise    (.deleteSecret js/window.electronAPI api-key-name)
     :on-success [[:settings.actions/remove-success]]
     :on-error   [[:settings.actions/remove-error]]}]])

;; Success/error handlers
(defn save-success [_state _result]
  (println "API key saved successfully!")
  [[:effects/save sensitive-state/api-key-input-path ""] ;; clears cached api key
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
