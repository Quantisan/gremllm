(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]))

(defn capture [_state {:keys [selection anchor]}]
  (if selection
    [[:effects/save excerpt-state/captured-path selection]
     [:effects/save excerpt-state/anchor-path anchor]]
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]])

(defn stage [state]
  (when-let [captured (get-in state excerpt-state/captured-path)]
    [[:staging.actions/stage captured]
     [:excerpt.actions/dismiss-popover]]))
