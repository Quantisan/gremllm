(ns gremllm.renderer.state.workspace)

(def loaded-path [:workspace :loaded?])
(def workspace-path-path [:workspace :path])

(defn loaded? [state]
  (get-in state loaded-path false))

(defn get-workspace-path [state]
  (get-in state workspace-path-path))
