(ns gremllm.renderer.ui.elements
  (:require [replicant.alias :refer [defalias]]))

(defalias app-layout [attrs body]
  (into [:div.app-layout (merge {} attrs)] body))

(defalias nav-strip [attrs & body]
  (into [:nav.nav-strip (merge {:data-theme "dark"} attrs)] body))

(defalias nav-overlay [attrs & body]
  (into [:aside.nav-overlay (merge {:data-theme "dark"} attrs)] body))

(defalias document-panel [attrs & body]
  (into [:section.document-panel (merge {} attrs)] body))

(defalias chat-panel [attrs & body]
  (into [:section.chat-panel (merge {:data-theme "dark"} attrs)] body))

(defalias modal [attrs body]
  (let [{:keys [open? on-close]} attrs
        attrs (dissoc attrs :open? :on-close)]
    [:dialog (assoc attrs :id "settings-dialog" :open open?)
     [:article
      [:header
       [:button {:aria-label "Close" :rel "prev" :class "close" :on {:click on-close}}]
       [:h3 "⚙️ Settings"]]
      body]]))

(defalias top-bar [attrs & body]
  (into [:header.top-bar (merge {} attrs)] body))

(defalias alert [attrs & body]
  (into [:article (merge {:role "alert"} attrs)] body))

(defalias chat-area [attrs & body]
  (into [:div.chat-area (merge {:id "chat-messages-container"} attrs)] body))

(defalias user-message [attrs & body]
  (into [:article.user-bubble attrs] body))

(defalias assistant-message [attrs & body]
  (into [:article.assistant-bubble attrs] body))

(defalias reasoning-message [attrs & body]
  [:article.reasoning-bubble
   [:details (merge {:open true} attrs)
    [:summary.reasoning-summary "Reasoning ..."]
    (into [:div] body)]])

(defalias topic-item [attrs & body]
  (into [:div.topic-item (merge {} attrs)] body))
