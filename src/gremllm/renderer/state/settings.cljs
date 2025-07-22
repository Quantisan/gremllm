(ns gremllm.renderer.state.settings)

;; Path to the API key input value in app state
(def api-key-input-path [:settings :api-key-input])

;; Getter function for API key input
(defn get-api-key-input [state]
  (get-in state api-key-input-path ""))