(ns gremllm.renderer.state.workspace)

(def workspace-path [:workspace])

(def loaded-path (conj workspace-path :loaded?))

(def workspace-name-path (conj workspace-path :name))

(defn get-workspace [state]
  (get-in state workspace-path))

(defn get-workspace-name [state]
  (get-in state workspace-name-path ""))

(defn loaded? [state]
  (get-in state loaded-path false))

