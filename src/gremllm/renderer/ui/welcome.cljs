(ns gremllm.renderer.ui.welcome)

(defn render-welcome []
  [:main {:class "container"
          :style {:text-align "center"
                  :margin-top "20vh"}}
   [:h1 "Gremllm"]
   [:button {:on {:click [[:document.actions/pick-document]]}}
    "Open a document…"]])
