(ns gremllm.renderer.state.ui)

;; Path constants
(def renaming-topic-id-path [:topics-ui :renaming-id])
(def nav-expanded-path [:ui :nav-expanded?])

(defn renaming-topic-id [state]
  (get-in state renaming-topic-id-path))

(defn nav-expanded? [state]
  (get-in state nav-expanded-path false))
