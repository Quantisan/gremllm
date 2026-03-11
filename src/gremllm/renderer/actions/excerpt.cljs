(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]))

(defn capture [_state selection-data]
  (when (and selection-data (not (:is-collapsed selection-data)))
    [[:effects/save excerpt-state/captured-path selection-data]]))
