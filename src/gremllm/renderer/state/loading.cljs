(ns gremllm.renderer.state.loading)

;; Path constants
(defn loading-path [topic-id] [:loading topic-id])

;; State accessor functions
(defn loading? [state topic-id]
  (get-in state [:loading topic-id] false))

