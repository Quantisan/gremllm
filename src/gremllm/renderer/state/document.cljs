(ns gremllm.renderer.state.document)

(def content-path [:document :content])

(defn get-content [state]
  (get-in state content-path))
