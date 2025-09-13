(ns gremllm.renderer.state.workspace)

(def loaded-path [:workspace :loaded?])

(defn loaded? [state]
  (get-in state loaded-path false))

