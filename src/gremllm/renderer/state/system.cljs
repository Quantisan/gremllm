(ns gremllm.renderer.state.system)

(def system-info-path [:system])
(def redacted-anthropic-api-key-path (conj system-info-path :secrets :anthropic-api-key))

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

(defn get-redacted-anthropic-api-key [state]
  (get-in state redacted-anthropic-api-key-path nil))

(defn has-anthropic-api-key? [state]
  (some? (get-redacted-anthropic-api-key state)))



