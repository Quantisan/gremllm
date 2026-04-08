(ns gremllm.renderer.state.excerpt)

;; TODO: consider moving this into schema/PersistedTopic
(def captured-path [:excerpt :captured])

(defn get-captured [state]
  (get-in state captured-path))

(def popover-path [:excerpt :popover])

(defn get-popover [state]
  (get-in state popover-path))
