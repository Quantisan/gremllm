(ns gremllm.renderer.state.form)

;; Path constants
(def user-input-path [:form :user-input])
(def selected-model-path [:form :selected-model])

;; State accessor functions
(defn get-user-input [state]
  (get-in state user-input-path ""))

(defn get-selected-model [state]
  (get-in state selected-model-path))
