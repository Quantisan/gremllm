(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.schema.codec :as codec]))

(defn capture [_state selection-data]
  (if selection-data
    (let [coerced (codec/captured-selection-from-dom selection-data)]
      [[:effects/save excerpt-state/captured-path coerced]
       [:excerpt.actions/compute-popover-position coerced]])
    [[:excerpt.actions/dismiss-popover]]))

(defn compute-popover-position [_state captured-data]
  (when (and captured-data (:panel-rect captured-data))
    (let [rects      (get-in captured-data [:range :client-rects])
          last-rect  (last rects)
          panel-rect (:panel-rect captured-data)
          scroll-top (:panel-scroll-top captured-data 0)
          top        (+ (- (+ (:top last-rect) (:height last-rect))
                           (:top panel-rect))
                        scroll-top)
          left       (- (:left last-rect) (:left panel-rect))]
      [[:effects/save excerpt-state/popover-path {:top top :left left}]])))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/popover-path nil]])
