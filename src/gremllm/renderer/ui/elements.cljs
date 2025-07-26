(ns gremllm.renderer.ui.elements
  (:require [replicant.alias :refer [defalias]]))

(defalias app-layout [attrs body]
  (into [:div (merge {:style {:display "flex"
                              :flex-direction "column"
                              :height "100vh"}}
                     attrs)]
        body))

(defalias modal [attrs body]
  (let [{:keys [open? on-close]} attrs
        attrs (dissoc attrs :open? :on-close)]
    [:dialog (assoc attrs :id "settings-dialog" :open open?)
     [:article
      [:header
       [:button {:aria-label "Close" :rel "prev" :class "close" :on {:click on-close}}]
       [:h3 "⚙️ Settings"]]
      body]]))

(defalias alert [attrs & body]
  (into [:article (merge {:role "alert"} attrs)] body))

(defalias chat-area [attrs & body]
  (into [:div.chat-area (merge {:id "chat-messages-container"
                                :style {:overflow-y "auto" :flex "1"}}
                               attrs)]
        body))

(defalias user-message [attrs & body]
  [:div.message-container
   (into [:article.user-bubble attrs] body)])

(defalias assistant-message [attrs & body]
  [:div.message-container
   (into [:article attrs] body)])
