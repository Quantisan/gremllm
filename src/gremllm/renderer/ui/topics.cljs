(ns gremllm.renderer.ui.topics)

(defn render-left-panel-content
  [{:keys [workspace-name workspace-description topics active-topic-id]}]
  [:div
   [:nav
    [:ul
     [:li
      [:a {:href "#"
           :on   {:click [[:effects/prevent-default]
                          [:topic.actions/start-new]]}}
       "➕ New Topic"]]]]
   [:hr]
   [:hgroup
    [:h4 workspace-name]
    [:p [:small workspace-description]]]
   [:nav
    [:ul
     (for [{:keys [id name]} topics]
       [:li
        [:a {:href         "#"
             :aria-current (when (= id active-topic-id) "page")
             :on           {:click [[:effects/prevent-default]
                                    [:topic.actions/switch-to id]]}}
         (str (if (= id active-topic-id) "✓ " "• ")
              (or name "Untitled"))]])]]])
