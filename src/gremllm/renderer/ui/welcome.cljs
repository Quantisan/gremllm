(ns gremllm.renderer.ui.welcome)

(defn render-welcome []
  [:main {:class "container"
          :style {:text-align "center"
                  :margin-top "20vh"}}
   [:header
    [:h1 "Welcome to Gremllm"]
    [:p "Open a workspace folder to begin your conversations"]]
   [:button {:on {:click [[:workspace.actions/pick-folder]]}}
    "Open Workspace Folder"]
   [:footer
    [:small "A workspace is like a project folder that contains all your related topics and conversations"]]])
