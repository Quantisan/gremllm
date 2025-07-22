(ns gremllm.renderer.state.topic)

;; State accessor functions
(defn get-messages [state]
  (get state :messages []))

