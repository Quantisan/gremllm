(ns gremllm.renderer.state.sensitive
  "State management for sensitive transient data that requires special handling.
   This data should be cleared immediately after use.")

;; Path constants for sensitive transient data
(def api-key-inputs-path [:sensitive :api-key-inputs])

;; State accessor functions
(defn get-api-key-input
  "Retrieves API key input for specified provider. Returns empty string if not found."
  [state provider]
  (get-in state (conj api-key-inputs-path provider) ""))