(ns gremllm.renderer.state.ui)

(defn showing-settings? [state]
  (get-in state [:ui :showing-settings?] false))
