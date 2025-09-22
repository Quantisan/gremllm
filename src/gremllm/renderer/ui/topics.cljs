(ns gremllm.renderer.ui.topics)

(defn render-left-panel-content
  ;; topics-map = schema/WorkspaceTopics
  [{:keys [workspace-name workspace-description topics-map active-topic-id]}]
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
     (for [{:keys [id name unsaved?]} (vals topics-map)]
       [:li
        [:a {:href         "#"
             :aria-current (when (= id active-topic-id) "page")
             :title        "Double-click to rename topic"
             :on           {:click    [[:effects/prevent-default]
                                       [:topic.actions/switch-to id]]
                            :dblclick [[:effects/prevent-default]
                                       [:topic.actions/rename id]]}}
         (str (if (= id active-topic-id) "✓ " "• ")
              name
              (when unsaved? " *"))]])]]])
