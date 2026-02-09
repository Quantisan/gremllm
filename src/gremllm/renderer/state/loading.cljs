(ns gremllm.renderer.state.loading)

;; Path constants
(defn loading-path [id] [:loading id])

;; State accessor functions
(defn get-loading [state]
  (get state :loading {}))

(defn loading? [state]
  (some true? (vals (get-loading state))))

