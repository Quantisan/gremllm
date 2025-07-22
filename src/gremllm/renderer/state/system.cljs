(ns gremllm.renderer.state.system)

(def system-info-path [:system])

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

(defn has-anthropic-api-key? [state]
  (contains? (get-in state (conj system-info-path :secrets) {}) "anthropic-api-key"))

