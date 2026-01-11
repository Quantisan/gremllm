(ns gremllm.renderer.actions.system
  (:require [gremllm.renderer.state.system :as system-state]
            [gremllm.schema :as schema]))

(defn set-info [_state system-info-js]
  [[:effects/save system-state/system-info-path (schema/system-info-from-ipc system-info-js)]])

(defn request-info [_state]
  [[:effects/promise
    {:promise    (js/window.electronAPI.getSystemInfo)
     :on-success [[:system.actions/set-info]]
     :on-error   [[:system.actions/request-error]]}]])

(defn request-error [_state error]
  [[:ui.effects/console-error "Failed to get system info:" error]])

