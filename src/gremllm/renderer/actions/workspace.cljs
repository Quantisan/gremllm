(ns gremllm.renderer.actions.workspace
  (:require [gremllm.schema.codec :as codec]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn mark-loaded
  "Mark the document as successfully loaded and ready for use."
  [_state]
  [[:effects/save document-state/loaded-path true]])

(defn set-workspace
  "Save document metadata into renderer state.
   Writes :name individually — must NOT replace the entire [:document] map."
  [_state document-meta]
  [[:effects/save document-state/name-path (:name document-meta)]])

(defn opened
  "A document has been opened/loaded from disk."
  [_state sync-data-js]
  (let [{:keys [topics document-meta document]} (codec/document-sync-from-ipc sync-data-js)]
    ;; TODO: When document revision tracking lands, compare the incoming document revision here and clear staged selections across topics on change.
    ;; Why: staged anchors are revision-bound workspace/topic context, so invalidation belongs at the workspace sync boundary rather than in a generic document setter.
    (cond-> [[:workspace.actions/set document-meta]
             [:document.actions/set-content (:content document)]]
      (empty? topics) (conj [:workspace.actions/initialize-empty])
      (seq topics)    (conj [:workspace.actions/restore-with-topics
                              {:topics          topics
                               ;; TODO: save last active topic id so that user can continue where they left off
                               :active-topic-id (ffirst topics)}]))))

(defn restore-with-topics
  "Restore a document that has existing topics."
  [_state {:keys [topics active-topic-id]}]
  [[:effects/save topic-state/topics-path topics]
   [:topic.actions/set-active active-topic-id]
   [:workspace.actions/mark-loaded]])

(defn initialize-empty
  "Initialize an empty document with its first topic."
  [_state]
  [[:topic.actions/start-new]
   [:workspace.actions/mark-loaded]])

(defn load-error [_state error]
  (js/console.error "document load failed:" error)
  ;; Don't auto-create anything on load error - let user handle it
  [])
