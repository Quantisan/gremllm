(ns gremllm.renderer.actions.excerpt
  (:require [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn capture->excerpt
  "Pure transform: ephemeral capture + locator-hints -> durable DocumentExcerpt.
   `id` is supplied by the caller so UUID generation stays outside this helper."
  [captured locator-hints id]
  {:id id
   :text (:text captured)
   :locator locator-hints})

(defn capture [_state {:keys [selection anchor locator-hints]}]
  (if (and selection locator-hints)
    [[:effects/save excerpt-state/captured-path selection]
     [:effects/save excerpt-state/anchor-path anchor]
     [:effects/save excerpt-state/locator-hints-path locator-hints]]
    [[:excerpt.actions/dismiss-popover]]))

(defn dismiss-popover [_state]
  [[:effects/save excerpt-state/captured-path nil]
   [:effects/save excerpt-state/anchor-path nil]
   [:effects/save excerpt-state/locator-hints-path nil]])

(defn add [state]
  (when-let [captured (get-in state excerpt-state/captured-path)]
    (when-let [topic-id (topic-state/get-active-topic-id state)]
      (let [locator-hints (get-in state excerpt-state/locator-hints-path)
            path          (topic-state/excerpts-path topic-id)
            existing      (or (get-in state path) [])
            id            (str "excerpt-" (random-uuid))
            excerpt       (capture->excerpt captured locator-hints id)]
        [[:effects/save path (conj existing excerpt)]
         [:topic.actions/mark-active-unsaved]
         [:topic.effects/auto-save topic-id]
         [:excerpt.actions/dismiss-popover]]))))

(defn remove-excerpt [state id]
  (when-let [topic-id (topic-state/get-active-topic-id state)]
    (let [path     (topic-state/excerpts-path topic-id)
          existing (or (get-in state path) [])]
      [[:effects/save path (vec (clojure.core/remove #(= (:id %) id) existing))]
       [:topic.actions/mark-active-unsaved]
       [:topic.effects/auto-save topic-id]])))

(defn clear-active [state]
  (when-let [topic-id (topic-state/get-active-topic-id state)]
    (let [path (topic-state/excerpts-path topic-id)]
      [[:effects/save path []]
       [:topic.actions/mark-active-unsaved]
       [:topic.effects/auto-save topic-id]])))

(defn consume [_state topic-id]
  [[:effects/save (topic-state/excerpts-path topic-id) []]])

(defn invalidate-across-topics [state]
  (mapv (fn [topic-id]
          [:effects/save (topic-state/excerpts-path topic-id) []])
        (keys (topic-state/get-topics-map state))))
