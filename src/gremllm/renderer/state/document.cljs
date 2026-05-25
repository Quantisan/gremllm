(ns gremllm.renderer.state.document)

(def content-path [:document :content])
(def meta-path [:document :meta])
(def loaded-path [:document :loaded?])

(defn get-content [state]
  (get-in state content-path))

(defn get-meta [state]
  (get-in state meta-path))

(defn loaded? [state]
  (get-in state loaded-path false))
