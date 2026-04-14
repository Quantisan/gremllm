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

;; TODO(design): Revisit whether document content updates should also own
;; cross-topic staged-selection invalidation; the boundary is still unclear.
(defn set-content [_state content]
  [[:effects/save document-state/content-path content]
   [:staging.actions/clear-staged-across-topics]
   [:excerpt.actions/dismiss-popover]])
