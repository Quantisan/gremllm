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

;; TODO(design): `set-content` currently does two jobs: replace document text
;; and clear excerpts across all topics. That is a leaky boundary because this
;; action is also used for workspace hydration/reload, so opening a workspace
;; can invalidate excerpts even when no user edit happened. There is also a
;; persistence pitfall: `:excerpt.actions/clear-across-topics` only mutates
;; renderer state, so a later reload can resurrect the cleared excerpts from
;; disk.
(defn set-content [_state content]
  [[:effects/save document-state/content-path content]
   [:excerpt.actions/clear-across-topics]
   [:excerpt.actions/dismiss-popover]])
