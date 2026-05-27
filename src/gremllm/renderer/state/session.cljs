(ns gremllm.renderer.state.session)

(def session-colors
  ["#E07634" "#3D8B8A" "#7B5EA7" "#C4534A" "#4A7B3F"])

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
