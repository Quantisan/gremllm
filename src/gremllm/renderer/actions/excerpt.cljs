(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]))

(defn capture->excerpt
  "Pure transform: ephemeral capture + locator-hints -> durable DocumentExcerpt.
   `id` is supplied by the caller so UUID generation stays outside this helper."
  [captured locator-hints id]
  {:id id
   :text (:text captured)
   :locator locator-hints})

(defn capture [_state {:keys [selection anchor locator-hints]}]
  (if selection
    [[:effects/save excerpt-state/captured-path selection]
     [:effects/save excerpt-state/anchor-path anchor]
     [:effects/save excerpt-state/locator-hints-path locator-hints]]
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]
   [:effects/save excerpt-state/locator-hints-path nil]])

(defn stage [state]
  (when-let [captured (get-in state excerpt-state/captured-path)]
    (let [locator-hints (get-in state excerpt-state/locator-hints-path)
          id (str "excerpt-" (random-uuid))
          excerpt (capture->excerpt captured locator-hints id)]
      [[:staging.actions/stage excerpt]
       [:excerpt.actions/dismiss-popover]])))
