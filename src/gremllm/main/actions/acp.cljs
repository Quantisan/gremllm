(ns gremllm.main.actions.acp
  "Pure actions for ACP prompt construction")

(defn prompt-content-blocks
  "Build ACP prompt content blocks from text and optional document path."
  [text document-path]
  (cond-> [{:type "text" :text text}]
    document-path
    (conj {:type "resource_link"
           :uri  (str "file://" document-path)
           :name "document.md"})))
