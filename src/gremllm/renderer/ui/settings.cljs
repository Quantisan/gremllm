(ns gremllm.renderer.ui.settings)

(defn render-settings [encryption-available?]
  [:div
   [:section
    [:h3 "API Keys"]
    (if-not encryption-available?
      [:div "⚠️ Secrets cannot be encrypted on this system"]
      [:p "API key storage is available."])]
   [:footer
    [:button
     {:on {:click [[:ui.actions/hide-settings]]}}
     "Done"]]])

(defn render-settings-modal [open?]
  [:dialog {:id "settings-dialog"
            :open open?}
   [:article
    [:header
     [:button {:aria-label "Close"
               :rel "prev"
               :on {:click [[:ui.actions/hide-settings]]}}]
     [:p [:strong "⚙️ Settings"]]]
    (render-settings false)]])

