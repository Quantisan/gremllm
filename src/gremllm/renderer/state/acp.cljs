(ns gremllm.renderer.state.acp)

(def acp-path [:acp])

;; Simplified state: only loading indicator
;; Session IDs now live in topics, chunks go directly to messages
(def loading-path (conj acp-path :loading?))

(defn loading? [state]
  (get-in state loading-path false))
