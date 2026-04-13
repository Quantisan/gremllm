(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn create [_state]
  [[:effects/promise
    {:promise    (.createDocument js/window.electronAPI)
     :on-success [[:document.actions/create-success]]
     :on-error   [[:document.actions/create-error]]}]])

(defn create-success [_state result-js]
  [[:document.actions/set-content (.-content result-js)]])

(defn create-error [_state error]
  [[:ui.effects/console-error "Failed to create document:" error]])

(defn set-content [state content]
  (let [topic-ids (keys (topic-state/get-topics-map state))]
    (into [[:effects/save document-state/content-path content]]
          (concat
            (map (fn [topic-id]
                   [:effects/save (topic-state/staged-selections-path topic-id) []])
                 topic-ids)
            [[:excerpt.actions/dismiss-popover]]))))
