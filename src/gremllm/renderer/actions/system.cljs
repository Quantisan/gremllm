(ns gremllm.renderer.actions.system
  (:require [gremllm.renderer.state.system :as system-state]
            [gremllm.schema :as schema]))

(defn set-info [_state system-info]
  (println "[RENDERER] system-info (raw from IPC):" system-info)
  (let [transformed (schema/system-info-from-ipc system-info)]
    (println "[RENDERER] system-info (after transform):" transformed)
    [[:effects/save system-state/system-info-path transformed]]))

(defn request-info [_state]
  [[:effects/promise
    {:promise    (js/window.electronAPI.getSystemInfo)
     :on-success [[:system.actions/set-info]]
     :on-error   [[:system.actions/request-error]]}]])

(defn request-error [_state error]
  [[:ui.effects/console-error "Failed to get system info:" error]])

