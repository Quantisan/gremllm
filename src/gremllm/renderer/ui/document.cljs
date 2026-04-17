(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.diffs :as diffs]
            [gremllm.renderer.ui.document.highlights :as highlights]
            [gremllm.renderer.ui.document.locator :as locator]))

(defn- render-diff-segments [segments]
  (into [:div]
        (mapv (fn [{:keys [type content old-text new-text]}]
                (case type
                  :text       [:span content]
                  :diff-block [:div.diff-block
                               [:del old-text]
                               [:ins new-text]
                               [:div.diff-controls
                                [:button {:on {:click [[:topic.actions/accept-diff
                                                        {:old-text old-text :new-text new-text}]]}}
                                 "Accept"]
                                [:button {:class "secondary outline"
                                          :on {:click [[:topic.actions/reject-diff
                                                        {:old-text old-text :new-text new-text}]]}}
                                 "Reject"]]]))
              segments)))

(defn- on-render-sync [content excerpts]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear!)
      ;; Replicant re-renders markdown into a fresh DOM subtree, which drops
      ;; any prior block decorations. Re-apply both after every render:
      ;; - sync-block-metadata! stamps source-line data-* attrs on each block
      ;;   so mouseup selections resolve to markdown coords via DOM .closest()
      ;;   (see locator/selection-locator-from-dom).
      ;; - highlights/sync! re-paints excerpt ranges against the new nodes.
      (do
        (locator/sync-block-metadata! node content)
        (highlights/sync! node excerpts)))))

(defn render-document [content pending-diffs excerpts]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        ;; Intentionally no selection capture in diff mode: review is modal here,
        ;; so accept/reject is the only allowed interaction (see 58cd32e).
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                  {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render (on-render-sync content excerpts)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
