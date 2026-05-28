(ns gremllm.renderer.state.session)

;; TODO(slice2): this namespace speaks "session" but operates on "topic" state/schema.
;; Reconcile the vocabulary when topic -> session rename lands (see actions.cljs).
;; Also extract a `shell?` predicate here — shell-ness (no ACP session id) is currently
;; detected two ways: ui.cljs via get-acp-session-id, chat.cljs via [:session :id]. When
;; ACP re-wires in Slice 2, both must update; one definition prevents a stale branch.

(def session-colors
  ["var(--session-color-1)"
   "var(--session-color-2)"
   "var(--session-color-3)"
   "var(--session-color-4)"
   "var(--session-color-5)"])

(def hovered-bar-topic-id-path [:ui :hovered-bar-topic-id])

(defn get-hovered-bar-topic-id [state]
  (get-in state hovered-bar-topic-id-path))

(defn anchored-topics-sorted
  [topics-map]
  (->> (vals topics-map)
       (filter :anchor)
       (sort-by :id)))

(defn most-recent-anchored
  [topics-map]
  (last (anchored-topics-sorted topics-map)))

(defn color-for-topic
  [topics-map topic-id]
  (let [sorted-ids (mapv :id (anchored-topics-sorted topics-map))
        idx (.indexOf sorted-ids topic-id)]
    (when-not (neg? idx)
      (nth session-colors (mod idx (count session-colors))))))
