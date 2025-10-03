(ns gremllm.renderer.ui.topics)

(defn- focus-and-select-on-mount
  "On-mount hook for rename input; focuses and selects all text (typical rename UX)."
  [{:replicant/keys [node]}]
  (.focus node)
  (.select node))

(defn- render-workspace-header [{:keys [name description]}]
  [:hgroup
   [:h4 (or name "")]
   [:p [:small (or description "")]]])

(defn- render-topic-item [active-topic-id renaming-topic-id {:keys [id name unsaved?]}]
  [:li {:replicant/key id}
   (if (= id renaming-topic-id)
     [:input {:type "text"
              :default-value name
              :replicant/on-mount focus-and-select-on-mount
              :on {:blur    [[:ui.actions/exit-topic-rename-mode id]]
                   :keydown [[:topic.effects/handle-rename-keys id]]}}]
     (let [label (str (if (= id active-topic-id) "✓ " "• ")
                      name
                      (when unsaved? " *"))]
       [:a {:href         "#"
            :aria-current (when (= id active-topic-id) "page")
            :title        "Double-click to rename topic"
            :on           {:click    [[:effects/prevent-default]
                                      [:topic.actions/set-active id]]
                           :dblclick [[:effects/prevent-default]
                                      [:topic.actions/begin-rename id]]}}
        label]))])

(defn render-left-panel-content
  ;; topics-map = schema/WorkspaceTopics
  [{:keys [workspace topics-map active-topic-id renaming-topic-id]}]
  [:div
   [:nav
    [:ul
     [:li
      [:a {:href "#"
           :on   {:click [[:effects/prevent-default]
                          [:topic.actions/start-new]]}}
       "➕ New Topic"]]]]
   [:hr]
   (render-workspace-header workspace)
   [:nav
    [:ul
     (for [t (vals topics-map)]
       (render-topic-item active-topic-id renaming-topic-id t))]]])
