(ns gremllm.renderer.state.session
  (:require [gremllm.renderer.state.loading :as loading-state]))

;; TODO: this namespace speaks "session" but operates on "topic" state/schema.
;; Reconcile the vocabulary when topic -> session rename lands (see actions.cljs).

(defn shell?
  "A shell session is anchored but has no live ACP session id yet -- covers a
   brand-new session, one still connecting, and one whose init failed. Single
   source of truth for shell-ness."
  [topic]
  (nil? (get-in topic [:session :id])))

(defn session-status
  "Single derived status for the active session, unifying shell-ness (is there a
   live ACP session?) with loading (is an async op in flight?). The UI switches
   on this one value instead of threading the two booleans separately.
     :no-session   -- no active topic
     :connecting   -- shell session, init in flight
     :disconnected -- shell session, idle or init failed (retry available)
     :busy         -- live session, awaiting prompt response
     :ready        -- live session, idle"
  [state topic-id topic]
  (let [loading? (loading-state/loading? state topic-id)]
    (cond
      (nil? topic-id) :no-session
      (shell? topic)  (if loading? :connecting :disconnected)
      loading?        :busy
      :else           :ready)))

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
  ;; TODO: we want to sort by last modified time
  ;;
  ;; Sort by :id == sort by creation time: ids are `topic-<timestamp>-<random>`,
  ;; so lexicographic order is creation order (see schema/generate-topic-id).
  (->> (vals topics-map)
       (filter :anchor)
       (sort-by :id)))

(defn most-recent-anchored
  [topics-map]
  (last (anchored-topics-sorted topics-map)))

(defn color-for-topic
  "Palette color for an anchored session, derived from its stable, immutable
   topic id. Keying on the id (not the live anchored-list position, and not the
   mutable ACP :session :id) keeps a bar's color fixed as sessions are added or
   removed and as the ACP session re-wires. nil when the session is not anchored.

   TODO: persist an assigned color slot at creation. With only five
   colors, distinct adjacent bars aren't guaranteed -- collisions are common past
   a couple of sessions; a persisted round-robin slot would stay both stable and
   distinct."
  [topics-map topic-id]
  (when (get-in topics-map [topic-id :anchor])
    (nth session-colors (mod (hash topic-id) (count session-colors)))))
