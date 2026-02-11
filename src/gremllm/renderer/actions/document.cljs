(ns gremllm.renderer.actions.document
  (:require [gremllm.renderer.state.document :as document-state]))

(defn set-content [_state document]
  [[:effects/save document-state/content-path (:content document)]])
