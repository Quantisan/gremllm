(ns gremllm.renderer.state.messages)

;; State accessor functions
(defn get-messages [state]
  (get state :messages []))

