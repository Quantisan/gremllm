(ns gremllm.renderer.actions.settings
  (:require [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.schema :as schema]))

;; Actions for API key management

(defn update-input [_state provider value]
  [[:effects/save (conj sensitive-state/api-key-inputs-path provider) value]])

(defn save-key [state provider]
  (let [api-key (sensitive-state/get-api-key-input state provider)
        storage-key-name (-> (schema/provider->api-key-keyword provider)
                             name)]
    (if (empty? api-key)
      [] ; No-op if empty
      [[:effects/promise
        {:promise    (.saveSecret js/window.electronAPI storage-key-name api-key)
         :on-success [[:settings.actions/save-success provider]]
         :on-error   [[:settings.actions/save-error provider]]}]])))

(defn remove-key [_state provider]
  (let [storage-key-name (-> (schema/provider->api-key-keyword provider)
                             name)]
    [[:effects/promise
      {:promise    (.deleteSecret js/window.electronAPI storage-key-name)
       :on-success [[:settings.actions/remove-success provider]]
       :on-error   [[:settings.actions/remove-error provider]]}]]))

;; Success/error handlers
(defn save-success [_state _result provider]
  (println "API key saved successfully!")
  [[:effects/save (conj sensitive-state/api-key-inputs-path provider) ""]
   [:system.actions/request-info]
   [:ui.actions/hide-settings]])

(defn save-error [_state error provider]
  (println "Failed to save" (schema/provider-display-name provider) "API key:" error)
  []) ; TODO: Show error to user

(defn remove-success [_state _result _provider]
  (println "API key removed successfully!")
  [[:system.actions/request-info]
   [:ui.actions/hide-settings]])

(defn remove-error [_state error provider]
  (println "Failed to remove" (schema/provider-display-name provider) "API key:" error)
  []) ; TODO: Show error to user
