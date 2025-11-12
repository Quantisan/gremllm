(ns gremllm.renderer.state.form)

;; Path constants
(def user-input-path [:form :user-input])
(def pending-attachments-path [:form :pending-attachments])

;; State accessor functions
(defn get-user-input [state]
  (get-in state user-input-path ""))

(defn get-pending-attachments [state]
  (get-in state pending-attachments-path []))
