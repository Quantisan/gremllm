(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema.codec :as codec]))

;; TODO: what's composite-data? Can this be considered trust boundary? Perhaps add a schema?
(defn capture [_state composite-data]
  (if composite-data
    (let [coerced (codec/captured-selection-from-dom (:selection composite-data))]
      [[:effects/save excerpt-state/captured-path coerced]
       [:effects/save excerpt-state/anchor-path (:anchor composite-data)]])
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]])
