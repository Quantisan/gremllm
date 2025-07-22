(ns gremllm.renderer.state.topic)

(def path [:topic])

(defn get-messages [state]
  (get-in state (conj path :messages) []))

