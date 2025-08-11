(ns gremllm.renderer.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [gremllm.renderer.ui :as ui]
            [gremllm.renderer.actions]))

(defn main []
  ;; Set up the atom
  (let [store (atom nil)
        el    (js/document.getElementById "app")]
    (.onMenuCommand js/window.electronAPI "topic/save"
                    (fn []
                      (nxr/dispatch store {} [[:topic.effects/save-active-topic]])))

    ;; TODO: remove. Menu should open a workspace, not topic
    (.onMenuCommand js/window.electronAPI "topic/open"
                    (fn []
                      (nxr/dispatch store {} [[:topic.effects/load-latest-topic]])))

    (.onMenuCommand js/window.electronAPI "menu:settings"
                    (fn []
                      (nxr/dispatch store {} [[:ui.actions/show-settings]])))

    ;; Render on every change
    (add-watch store ::render-topic
               (fn [_ _ _ state]
                 (->> state
                      (ui/render-app)
                      (r/render el))))

    (r/set-dispatch!
      (fn [dispatch-data actions]
        (nxr/dispatch store dispatch-data actions)))

    ;; Trigger the first render
    (nxr/dispatch store {}
                  [[:system.actions/request-info]
                   [:topic.actions/bootstrap]])))
