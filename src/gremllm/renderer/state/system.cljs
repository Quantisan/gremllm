(ns gremllm.renderer.state.system)

(def system-info-path [:system :info])

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

