(ns gremllm.renderer.state.system
  (:require [gremllm.schema :as schema]))

;; TODO: we should create some path vars for DRY. Also, some of these maybe should be in the state.sensitive ns instead...

(def system-info-path [:system])

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

(defn get-redacted-api-key
  "Retrieves redacted API key for specified provider from state."
  [state provider]
  (get-in state (conj system-info-path :secrets :api-keys provider) nil))

(defn has-anthropic-api-key? [state]
  (some? (get-redacted-api-key state :anthropic)))

(defn has-api-key?
  "Returns true if the specified provider has an API key configured.
   Generic version of has-anthropic-api-key? that works for any provider."
  [state provider]
  (some? (get-redacted-api-key state provider)))

(defn get-all-redacted-api-keys
  "Retrieves a map of all providers to their redacted API keys."
  [state]
  (into {} (for [p schema/supported-providers]
             [p (get-redacted-api-key state p)])))



