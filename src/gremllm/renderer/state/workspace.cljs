(ns gremllm.renderer.state.workspace)

(def loaded-path [:workspace :loaded?])

(def workspace-path [:workspace])

(defn get-workspace [state]
  (get-in state workspace-path))

(defn loaded? [state]
  (get-in state loaded-path false))

