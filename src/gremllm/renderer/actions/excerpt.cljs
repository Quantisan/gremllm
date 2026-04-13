(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]))

(defn capture [_state {:keys [selection anchor locator-debug]}]
  (if selection
    (cond-> [[:effects/save excerpt-state/captured-path selection]
             [:effects/save excerpt-state/anchor-path anchor]
             [:effects/save excerpt-state/locator-debug-path locator-debug]]
      locator-debug (conj [:ui.effects/console-log "[excerpt-locator-spike]" locator-debug]))
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]
   [:effects/save excerpt-state/locator-debug-path nil]])

(defn stage [state]
  (when-let [captured (get-in state excerpt-state/captured-path)]
    [[:staging.actions/stage captured]
     [:excerpt.actions/dismiss-popover]]))
