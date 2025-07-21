(ns gremllm.renderer.state.ui)

;; Path constants
(def showing-settings-path [:ui :showing-settings?])

(defn showing-settings? [state]
  (get-in state showing-settings-path false))
