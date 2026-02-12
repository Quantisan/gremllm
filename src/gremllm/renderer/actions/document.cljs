(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]))

(defn create [_state]
  [[:effects/promise
    {:promise    (.createDocument js/window.electronAPI)
     :on-success [[:document.actions/create-success]]
     :on-error   [[:document.actions/create-error]]}]])

(defn create-success [_state result-js]
  [[:effects/save document-state/content-path (.-content result-js)]])

(defn create-error [_state error]
  [[:ui.effects/console-error "Failed to create document:" error]])

(defn set-content [_state document]
  [[:effects/save document-state/content-path (:content document)]])
