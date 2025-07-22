(ns gremllm.renderer.state.sensitive
  "State management for sensitive transient data that requires special handling.
   This data should be cleared immediately after use.")

;; Path constants for sensitive transient data
(def api-key-input-path [:sensitive :api-key-input])

;; State accessor functions
(defn get-api-key-input [state]
  (get-in state api-key-input-path ""))