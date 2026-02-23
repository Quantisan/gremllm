(ns gremllm.renderer.state.document)

(def content-path [:document :content])

(defn get-content [state]
  (get-in state content-path))

;; S4b: Diffs stored as-is from codec output. Shape may evolve when
;; renderer consumes this for UI display (S5).
(def pending-diffs-path [:document :pending-diffs])
