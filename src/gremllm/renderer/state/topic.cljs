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

(defn get-pending-diffs [state]
  (get-in (get-active-topic state) [:session :pending-diffs]))

(defn pending-diffs-path [topic-id]
  (-> topics-path (conj topic-id :session :pending-diffs)))

(defn get-acp-session-id [state topic-id]
  (get-in (get-topic state topic-id) [:session :id]))

(defn acp-session-id-path [topic-id]
  ;; TODO: add reverse lookup from acp-session-id to topic-id for correct
  ;; inbound routing of session updates to the originating topic
  (-> topics-path (conj topic-id :session :id)))

