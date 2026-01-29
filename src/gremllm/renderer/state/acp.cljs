(ns gremllm.renderer.state.acp)

(def acp-path [:acp])

;; TODO: loading what? it's not clear
(def loading-path (conj acp-path :loading?))

(defn loading? [state]
  (get-in state loading-path false))
