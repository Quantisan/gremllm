(ns gremllm.renderer.state.excerpt)

(def captured-path [:excerpt :captured])

(defn get-captured [state]
  (get-in state captured-path))
