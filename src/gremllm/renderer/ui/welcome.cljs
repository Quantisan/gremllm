(ns gremllm.renderer.ui.welcome)

(defn render-welcome []
  [:main {:class "container"
          :style {:text-align "center"
                  :margin-top "20vh"}}
   [:header
    [:h1 "Welcome to Gremllm"]
    [:p "Open a Markdown document to begin"]]
   [:button {:on {:click [[:document.actions/pick-document]]}}
    "Open…"]
   [:footer
    [:small "Open any .md file from anywhere on your machine."]]])
