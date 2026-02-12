(ns gremllm.main.actions.document
  "Pure actions for document operations"
  (:require [gremllm.main.io :as io]))

(defn create-plan [workspace-dir]
  {:filepath (io/document-file-path workspace-dir)
   :content  "# Untitled Document\n"})
