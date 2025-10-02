(ns gremllm.renderer.state.topic)

(def topics-path [:topics])
(def active-topic-id-path [:active-topic-id])

(defn topic-path [topic-id]
  (conj topics-path topic-id))

(defn get-topic [state topic-id]
  (get-in state (topic-path topic-id)))

(defn get-active-topic-id [state]
  (get-in state active-topic-id-path))


(defn get-active-topic [state]
  (let [active-id (get-active-topic-id state)]
    (get-topic state active-id)))

(defn get-messages [state]
  (:messages (get-active-topic state)))

(defn get-topics-map [state]
  (or (get-in state topics-path) {}))

(defn topic-field-path [topic-id field]
  (-> topics-path (conj topic-id field)))

(defn get-topic-field [state topic-id field]
  (get-in state (topic-field-path topic-id field)))

