(ns gremllm.renderer.state.ui)

;; Path constants
(def showing-settings-path [:ui :showing-settings?])
(def renaming-topic-id-path [:topics-ui :renaming-id])

(defn showing-settings? [state]
  (get-in state showing-settings-path false))

(defn renaming-topic-id [state]
  (get-in state renaming-topic-id-path))
