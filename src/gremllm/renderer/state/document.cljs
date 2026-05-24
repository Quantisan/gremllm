(ns gremllm.renderer.state.document)

(def content-path [:document :content])
(def name-path [:document :name])
(def loaded-path [:document :loaded?])

(defn get-content [state]
  (get-in state content-path))

(defn get-name [state]
  (get-in state name-path))

(defn get-meta [state]
  (select-keys (get state :document) [:name]))

(defn loaded? [state]
  (get-in state loaded-path false))
