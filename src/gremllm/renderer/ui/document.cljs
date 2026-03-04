(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.anchoring :as anchoring]
            [gremllm.renderer.ui.document.composition :as composition]))

(defn- render-diff-segments [segments]
  (into [:div]
        (mapv (fn [{:keys [type content old-text new-text]}]
                (case type
                  :text       [:span content]
                  :diff-block [:div.diff-block [:del old-text] [:ins new-text]]))
              segments)))

(defn render-document [content pending-diffs]
  (if content
    (if (seq pending-diffs)
      (let [anchored (anchoring/anchor-diffs content pending-diffs)
            segments (composition/compose-diff-segments content anchored)]
        [:article.diff-mode (render-diff-segments segments)])
      [:article (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
