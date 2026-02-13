(ns gremllm.main.actions.acp
  "Pure actions for ACP prompt construction"
  (:require [gremllm.main.io :as io]))

(defn prompt-content-blocks
  "Build ACP prompt content blocks from text and optional document path."
  [text document-path]
  (cond-> [{:type "text" :text text}]

    document-path (conj {:type "resource_link"
                         :uri  (io/path->file-uri document-path)
                         ;; TODO: parse file name from document-path
                         :name "document.md"})))
