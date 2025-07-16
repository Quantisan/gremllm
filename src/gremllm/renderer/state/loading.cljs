(ns gremllm.renderer.state.loading)

;; Path constants
(defn loading-path [id] [:loading id])
(defn assistant-errors-path [id] [:assistant-errors id])

;; State accessor functions
(defn get-loading [state]
  (get state :loading {}))

(defn loading? [state]
  (some true? (vals (get-loading state))))

(defn get-assistant-errors [state]
  (get state :assistant-errors {}))

