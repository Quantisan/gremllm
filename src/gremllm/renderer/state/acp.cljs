(ns gremllm.renderer.state.acp)

(def acp-path [:acp])

(def session-id-path (conj acp-path :session-id))
(def chunks-path (conj acp-path :chunks))
(def loading-path (conj acp-path :loading?))

(defn get-session-id [state]
  (get-in state session-id-path))

(defn get-chunks [state]
  (get-in state chunks-path []))

(defn loading? [state]
  (get-in state loading-path false))
