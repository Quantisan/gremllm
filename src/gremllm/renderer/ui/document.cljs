(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.diffs :as diffs]
            [gremllm.renderer.ui.document.highlights :as highlights]
            [gremllm.renderer.ui.document.locator :as locator]
            [gremllm.renderer.ui.document.gutter :as gutter]))

(defn- render-diff-segments [segments]
  (into [:div]
        (mapv (fn [{:keys [type content old-text new-text tool-call-id]}]
                (case type
                  :text       [:span content]
                  :diff-block [:div.diff-block
                               [:del old-text]
                               [:ins new-text]
                               [:div.diff-controls
                                [:button {:on {:click [[:topic.actions/accept-diff tool-call-id]]}}
                                 "Accept"]
                                [:button {:class "secondary outline"
                                          :on {:click [[:topic.actions/reject-diff tool-call-id]]}}
                                 "Reject"]]]))
              segments)))

(defn- on-render-sync [content excerpts session-opts]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear-all!)
      (let [article node
            gutter-el (some-> article .-parentElement (.querySelector ".session-gutter"))]
        (locator/sync-block-metadata! article content)
        (highlights/sync! article excerpts)
        (highlights/sync-anchor! article (:active-anchor-text session-opts))
        (highlights/sync-anchor-preview! article (:preview-anchor-text session-opts))
        ;; gutter bars are positioned from block rects — requires sync-block-metadata! above
        (when gutter-el
          (gutter/sync! gutter-el article (:topics-map session-opts)))))))

(defn render-document [content pending-diffs excerpts session-opts]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                  {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render (on-render-sync content excerpts session-opts)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document open."]
     [:button {:on {:click [[:document.actions/pick]]}}
      "Open…"]]))
