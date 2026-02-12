(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]))

(defn render-document [content]
  (if content
    [:article
     [:div {:innerHTML (md/markdown->html content)}]]
    [:article
     [:p {:style {:color "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document in this workspace."]
     [:button {:on {:click [[:document.actions/create]]}}
      "Create Document"]]))
