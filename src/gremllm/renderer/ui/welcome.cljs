(ns gremllm.renderer.ui.welcome)

(defn render-welcome []
  [:main {:class "container"}
   [:article {:style {:text-align "center"
                      :margin-top "20vh"}}
    [:header
     [:h1 "Welcome to Gremllm"]
     [:p "Open a workspace folder to begin organizing your conversations"]]
    [:button {:on {:click [[:workspace.actions/open-folder]]}}
     "Open Workspace Folder"]
    [:footer
     [:small "A workspace is like a project folder that contains all your related topics and conversations"]]]])
