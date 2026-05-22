(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]))

;; Design note: `set-content` currently does two jobs: replace document text
;; and invalidate excerpts across all topics. That is a leaky boundary because
;; this action is also used for workspace hydration/reload, so opening a
;; workspace can invalidate excerpts even when no user edit happened. There is
;; also a persistence pitfall: `:excerpt.actions/invalidate-across-topics`
;; only mutates renderer state, so a later reload can resurrect the invalidated
;; excerpts from disk.
(defn pick
  "Initiate the file picker to open a document."
  [_state]
  [[:effects/promise
    {:promise (.pickDocument js/window.electronAPI)}]])

(defn set-content [_state content]
  [[:effects/save document-state/content-path content]
   [:excerpt.actions/invalidate-across-topics]
   [:excerpt.actions/dismiss-popover]])
