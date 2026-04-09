(ns gremllm.renderer.state.excerpt)

;; TODO: consider moving this into schema/PersistedTopic
(def captured-path [:excerpt :captured])

(defn get-captured [state]
  (get-in state captured-path))

(def anchor-path [:excerpt :anchor])

(defn get-anchor [state]
  (get-in state anchor-path))

(defn popover-position
  "Derives popover {top, left} from selection geometry and anchor context.
   Returns nil if either input is missing or anchor lacks panel-rect."
  [captured-selection anchor-context]
  (when (and captured-selection anchor-context (:panel-rect anchor-context))
    (let [rects      (get-in captured-selection [:range :client-rects])
          last-rect  (peek rects)
          panel-rect (:panel-rect anchor-context)
          scroll-top (:panel-scroll-top anchor-context)]
      (when last-rect
        {:top  (+ (- (+ (:top last-rect) (:height last-rect))
                     (:top panel-rect))
                  scroll-top)
         :left (- (:left last-rect)
                  (:left panel-rect))}))))
