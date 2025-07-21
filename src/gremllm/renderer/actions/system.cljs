(ns gremllm.renderer.actions.system
  (:require [gremllm.renderer.state.system :as system-state]))

(defn set-info [_state system-info]
  [[:effects/save system-state/system-info-path system-info]])

