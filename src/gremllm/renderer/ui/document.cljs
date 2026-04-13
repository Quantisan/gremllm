(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.diffs :as diffs]
            [gremllm.renderer.ui.document.highlights :as highlights]))

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

(defn- on-render-sync [staged-selections]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear!)
      (highlights/sync! node staged-selections))))

(defn render-document [content pending-diffs staged-selections]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        ;; Intentionally no selection capture in diff mode: review is modal here,
        ;; so accept/reject is the only allowed interaction (see 58cd32e).
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                   {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render  (on-render-sync staged-selections)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
