(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]))

(defn- render-diff-segments [segments]
  (into [:div]
        (mapv (fn [{:keys [type content old-text new-text]}]
                (case type
                  :text       [:span content]
                  :diff-block [:div.diff-block [:del old-text] [:ins new-text]]))
              segments)))

(defn render-document [content]
  (if content
    [:article (md/markdown->hiccup content)]
    [:article
     [:p {:style {:color "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
