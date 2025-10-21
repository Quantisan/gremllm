(ns gremllm.renderer.state.sensitive
  "State management for sensitive transient data that requires special handling.
   This data should be cleared immediately after use."
  (:require [gremllm.schema :as schema]
            [gremllm.renderer.state.system :as system]))

;; Path constants for sensitive transient data
(def api-key-inputs-path [:sensitive :api-key-inputs])

;; State accessor functions
(defn get-api-key-input
  "Retrieves API key input for specified provider. Returns nil if not found."
  [state provider]
  (get-in state (conj api-key-inputs-path provider)))

(defn get-all-api-key-inputs
  "Retrieves a map of all providers to their API key input values."
  [state]
  (into {} (for [p schema/supported-providers]
             [p (get-api-key-input state p)])))

(defn settings-view-props
  "Returns map with :encryption-available?, :api-keys, :api-key-inputs."
  [state]
  {:encryption-available? (system/encryption-available? state)
   :api-keys (system/get-all-redacted-api-keys state)
   :api-key-inputs (get-all-api-key-inputs state)})