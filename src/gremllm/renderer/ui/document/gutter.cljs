(ns gremllm.renderer.ui.document.gutter
  (:require [gremllm.renderer.state.session :as session-state]
            [gremllm.renderer.ui.elements :as e]))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- render-bar [topic active? color]
  (let [topic-id (:id topic)
        anchor-text (get-in topic [:anchor :text])]
    [:button.session-bar-target
     {:replicant/key topic-id
      :aria-label   (str "Session: " (truncate anchor-text 40))
      :aria-pressed (if active? "true" "false")
      :data-topic-id topic-id
      :style {:--bar-color color}
      :on {:click      [[:effects/stop-propagation]
                        [:topic.actions/set-active topic-id]]
           :mouseenter [[:effects/save session-state/hovered-bar-topic-id-path topic-id]]
           :mouseleave [[:effects/save session-state/hovered-bar-topic-id-path nil]]}}
     [:div.session-bar
      {:class (if active? "session-bar--active" "session-bar--inactive")
       :style {:background-color color}}]]))

(defn render-gutter
  [topics-map active-topic-id]
  (let [anchored (session-state/anchored-topics-sorted topics-map)]
    [e/session-gutter
     (for [topic anchored]
       (let [active? (= (:id topic) active-topic-id)
             color   (session-state/color-for-topic topics-map (:id topic))]
         (render-bar topic active? color)))]))

(defn sync!
  [gutter article topics-map]
  (let [block-selector "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,table"
        blocks (.querySelectorAll article block-selector)
        article-rect (.getBoundingClientRect article)]
    (doseq [btn (.querySelectorAll gutter ".session-bar-target")]
      (let [topic-id (.getAttribute btn "data-topic-id")
            topic (get topics-map topic-id)
            anchor (:anchor topic)]
        (when anchor
          (let [start-idx (get-in anchor [:locator :start-block :index])
                end-idx   (get-in anchor [:locator :end-block :index])
                start-el  (some #(when (= (str start-idx)
                                          (.getAttribute % "data-grem-block-index"))
                                   %)
                                (array-seq blocks))
                end-el    (some #(when (= (str end-idx)
                                          (.getAttribute % "data-grem-block-index"))
                                  %)
                                (array-seq blocks))]
            (when (and start-el end-el)
              (let [start-rect (.getBoundingClientRect start-el)
                    end-rect   (.getBoundingClientRect end-el)
                    top        (- (.-top start-rect) (.-top article-rect))
                    height     (- (+ (.-top end-rect) (.-height end-rect))
                                 (.-top start-rect))]
                (set! (.. btn -style -top) (str top "px"))
                (set! (.. btn -style -height) (str height "px"))))))))))
