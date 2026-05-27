(ns gremllm.renderer.state.ui)

(def renaming-topic-id-path [:topics-ui :renaming-id])

(defn renaming-topic-id [state]
  (get-in state renaming-topic-id-path))
