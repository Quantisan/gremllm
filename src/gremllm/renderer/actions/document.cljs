(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]))

(defn create [_state]
  [[:effects/promise
    {:promise    (.createDocument js/window.electronAPI)
     :on-success [[:document.actions/create-success]]
     :on-error   [[:document.actions/create-error]]}]])

(defn create-success [_state result-js]
  [[:document.actions/set-content (.-content result-js)]])

(defn create-error [_state error]
  [[:ui.effects/console-error "Failed to create document:" error]])

(defn set-content [_state content]
  [[:effects/save document-state/content-path content]])

(defn append-pending-diffs [state diffs]
  (let [existing (get-in state document-state/pending-diffs-path [])]
    [[:effects/save document-state/pending-diffs-path (into existing diffs)]]))
