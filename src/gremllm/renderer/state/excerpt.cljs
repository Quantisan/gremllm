(ns gremllm.renderer.state.excerpt)

;; TODO: consider moving this into schema/PersistedTopic
(def captured-path [:excerpt :captured])

(defn get-captured [state]
  (get-in state captured-path))
