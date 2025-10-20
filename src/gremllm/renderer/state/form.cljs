(ns gremllm.renderer.state.form)

;; Path constants
(def user-input-path [:form :user-input])

;; State accessor functions
(defn get-user-input [state]
  (get-in state user-input-path ""))
