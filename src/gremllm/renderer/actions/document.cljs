(ns gremllm.renderer.actions.document
  (:require [gremllm.schema.codec :as codec]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.session :as session-state]))

;; Design note: `set-content` currently does two jobs: replace document text
;; and invalidate excerpts across all topics. That is a leaky boundary because
;; this action is also used for document hydration/reload, so opening a
;; document can invalidate excerpts even when no user edit happened. There is
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

(defn mark-loaded
  "Mark the document as successfully loaded and ready for use."
  [_state]
  [[:effects/save document-state/loaded-path true]])

(defn set-meta
  "Save document metadata into renderer state."
  [_state document-meta]
  [[:effects/save document-state/meta-path document-meta]])

(defn opened
  "A document has been opened/loaded from disk."
  [_state sync-data-js]
  (let [{:keys [topics document-meta document]} (codec/document-sync-from-ipc sync-data-js)]
    (cond-> [[:document.actions/set-meta document-meta]
             [:document.actions/set-content (:content document)]]
      (empty? topics) (conj [:document.actions/initialize-empty])
      (seq topics)    (conj [:document.actions/restore-with-topics
                              {:topics topics}]))))

(defn restore-with-topics
  "Restore a document that has existing topics.
   Auto-activates the most recently created anchored session.
   TODO(slice2): unanchored topics are invisible until anchor persistence lands."
  [_state {:keys [topics]}]
  (let [recent (session-state/most-recent-anchored topics)]
    (cond-> [[:effects/save topic-state/topics-path topics]]
      recent (conj [:topic.actions/set-active (:id recent)])
      true   (conj [:document.actions/mark-loaded]))))

(defn initialize-empty
  "Initialize an empty document with no active session."
  [_state]
  [[:document.actions/mark-loaded]])

(defn load-error [_state error]
  (js/console.error "document load failed:" error)
  ;; Don't auto-create anything on load error - let user handle it
  [])
