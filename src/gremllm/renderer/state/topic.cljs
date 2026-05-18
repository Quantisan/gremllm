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

(defn resolved-tool-calls-path [topic-id]
  (-> topics-path (conj topic-id :session :resolved-tool-calls)))

(defn get-resolved-tool-calls
  "Return the set of resolved tool-call-ids for topic-id, or empty set."
  [state topic-id]
  (or (get-in state (resolved-tool-calls-path topic-id)) #{}))

(defn pending-permission-options-path [topic-id]
  (-> topics-path (conj topic-id :session :pending-permission-options)))

(defn get-pending-permission-options
  "Return the options vector the agent sent with the pending permission
   for tool-call-id, or nil if no entry is stashed."
  [state topic-id tool-call-id]
  (get-in state (conj (pending-permission-options-path topic-id) tool-call-id)))

(defn excerpts-path [topic-id]
  (conj (topic-path topic-id) :excerpts))

(defn get-excerpts [state]
  (or (:excerpts (get-active-topic state)) []))

(defn get-acp-session-id [state topic-id]
  (get-in (get-topic state topic-id) [:session :id]))

(defn acp-session-id-path [topic-id]
  ;; TODO (inbound-routing): renderer ACP inbound handlers dispatch to the
  ;; active topic, not to the topic whose agent emitted the event. Cross-turn
  ;; topic switches land session updates, pending diffs, and pending
  ;; permissions on the wrong topic.
  ;;
  ;; Fix: reverse lookup from acp-session-id to topic-id, resolved at the IPC
  ;; boundary in renderer/core.cljs before per-domain actions dispatch.
  ;;
  ;; Affected: actions/topic.cljs append-pending-diffs and
  ;; append-pending-permission; core.cljs onAcpSessionUpdate.
  ;;
  ;; Click-time accept/reject is defended by construction: Accept/Reject
  ;; buttons render only on the topic owning :pending-diffs, so click-time
  ;; and append-time active-topic always match. The gap is purely at IPC
  ;; arrival.
  (-> topics-path (conj topic-id :session :id)))

(defn find-message-index-by-tool-call-id
  "Returns the index of the first message in the active topic with a matching
   :tool-call-id, or nil if no match."
  [state tool-call-id]
  (some (fn [[idx msg]]
          (when (= tool-call-id (:tool-call-id msg))
            idx))
        (map-indexed vector (get-messages state))))
