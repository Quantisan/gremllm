(ns gremllm.renderer.state.workspace)

(def workspace-path [:workspace])

(def loaded-path (conj workspace-path :loaded?))

(defn get-workspace [state]
  (get-in state workspace-path))

(defn loaded? [state]
  (get-in state loaded-path false))

