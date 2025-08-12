(ns gremllm.renderer.state.topic)

(def topics-path [:topics])
(def active-topic-id-path [:active-topic-id])

(defn get-active-topic-id [state]
  (get-in state active-topic-id-path))

(defn get-active-topic [state]
  (let [active-id (get-active-topic-id state)]
    (get-in state (conj topics-path active-id))))

(defn get-messages [state]
  (:messages (get-active-topic state)))

(defn get-topics-map [state]
  (or (get-in state topics-path) {}))

(defn get-topics [state]
  (-> (get-topics-map state) vals vec))

