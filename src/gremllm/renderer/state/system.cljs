(ns gremllm.renderer.state.system)

(def system-info-path [:system])
(def redacted-anthropic-api-key-path (conj system-info-path :secrets :api-keys :anthropic))

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

(defn get-redacted-api-key
  "Retrieves redacted API key for specified provider from state."
  [state provider]
  (get-in state (conj system-info-path :secrets :api-keys provider) nil))

(defn get-redacted-anthropic-api-key [state]
  (get-in state redacted-anthropic-api-key-path nil))

(defn has-anthropic-api-key? [state]
  (some? (get-redacted-api-key state :anthropic)))



