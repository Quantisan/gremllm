(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema.codec :as codec]))

(defn capture [_state selection-data]
  (when selection-data
    [[:effects/save excerpt-state/captured-path
      (codec/captured-selection-from-dom selection-data)]]))
