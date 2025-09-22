(ns gremllm.renderer.state.topic)

(def topics-path [:topics])
(def active-topic-id-path [:active-topic-id])
(def renaming-topic-id-path [:topics-ui :renaming-id])

(defn get-active-topic-id [state]
  (get-in state active-topic-id-path))

(defn renaming-topic-id [state]
  (get-in state renaming-topic-id-path))

(defn get-active-topic [state]
  (let [active-id (get-active-topic-id state)]
    (get-in state (conj topics-path active-id))))

(defn get-messages [state]
  (:messages (get-active-topic state)))

(defn get-topics-map [state]
  (or (get-in state topics-path) {}))

(defn get-topics [state]
  (-> (get-topics-map state) vals vec))

(defn topic-field-path [topic-id field]
  (-> topics-path (conj topic-id field)))

