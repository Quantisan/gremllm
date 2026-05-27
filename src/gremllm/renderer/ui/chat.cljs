(ns gremllm.renderer.ui.chat
  (:require [clojure.string :as str]
            [gremllm.renderer.ui.elements :as e]
            [gremllm.renderer.ui.markdown :as md]))

(defn- excerpt-block-label
  "Short advisory locator label like p3 or h1 -> p2."
  [{{:keys [start-block end-block]} :locator}]
  (let [prefix (fn [{:keys [kind]}]
                 (case kind
                   :heading "h"
                   :paragraph "p"
                   :list-item "li"
                   :code-block "code"
                   :blockquote "bq"
                   :table "tbl"
                   (name kind)))
        start (str (prefix start-block) (:index start-block))
        end (str (prefix end-block) (:index end-block))]
    (if (= start end) start (str start " -> " end))))

(def ^:private excerpt-snippet-cap 40)
(def ^:private web-search-query-cap 60)
(def ^:private composer-excerpt-cap 30)

(defn- render-assistant-message [message]
  [e/assistant-message
    (md/markdown->hiccup (:text message))])

(defn- render-reasoning-message [message]
  [e/reasoning-message {}
   (md/markdown->hiccup (:text message))])

(defn- truncate [s n]
  (if (> (count s) n)
    (str (subs s 0 n) "…")
    s))

(defn- render-tool-read [message]
  [e/tool-status-message
   [:span (:text message)]])

(defn- render-web-search [{:keys [tool-call-status query]}]
  (let [completed? (= "completed" tool-call-status)
        label      (if completed? "Searched the web" "Searching the web")
        summary    (if query (str label " — " (truncate query web-search-query-cap)) label)]
    [e/tool-detail-message {:completed? completed? :summary summary :query query}]))

(defn- render-tool-call-message [{:keys [tool] :as message}]
  (case tool
    :web-search (render-web-search message)
    :read       (render-tool-read message)
    [:div "Unknown tool:" tool]))

(defn- render-excerpt-pill [excerpt]
  [:span.excerpt-pill
   [:span.excerpt-pill__label (excerpt-block-label excerpt)]
   [:span.excerpt-pill__text (truncate (:text excerpt) excerpt-snippet-cap)]])

(defn- render-references [excerpts]
  ;; TODO(ui): This References row is an interim presentation. Excerpts should
  ;; render as visually distinct DOM nodes in the chat thread, not as content
  ;; inside the user message bubble. Leave the exact thread structure open for
  ;; follow-up work.
  [:div.message-references
   [:span.message-references__label "References:"]
   (into [:span.message-references__pills]
         (map render-excerpt-pill excerpts))])

(defn- render-user-message [{:keys [text context]}]
  [e/user-message
   [:span text]
   (when-let [excerpts (seq (:excerpts context))]
     (render-references excerpts))])

(defn- render-message [message]
  (case (:type message)
    :user      (render-user-message message)
    :assistant (render-assistant-message message)
    :reasoning (render-reasoning-message message)
    :tool-call (render-tool-call-message message)
    [:div "Unknown message type:" (:type message)]))

(defn- render-loading-indicator []
  [e/assistant-message
    [:p {:style {:color "var(--pico-muted-color)"
                 :font-style "italic"}}
     [:span {:style {:display "inline-block"
                     :animation "spin 1s linear infinite"
                     :margin-right "0.5rem"}}
      "⠿"]
     "Computing..."]])

(defn render-chat-area [messages awaiting-response? session-opts]
  (let [{:keys [active-topic active-topic-id]} session-opts]
    (cond
      (nil? active-topic-id)
      [e/chat-area
       [:div {:style {:display "flex"
                      :align-items "center"
                      :justify-content "center"
                      :height "100%"
                      :color "var(--pico-muted-color)"
                      :font-style "italic"}}
        "Select text in the document to start a session."]]

      ;; TODO(slice2): connect ACP; shell sessions show disabled placeholder
      (and active-topic (nil? (get-in active-topic [:session :id])))
      [e/chat-area
       [:div {:style {:padding "var(--pico-spacing)"}}
        [:blockquote {:style {:border-left-color "var(--pico-primary)"
                              :font-size "0.9rem"
                              :opacity 0.8}}
         (get-in active-topic [:anchor :text])]
        [:p {:style {:color "var(--pico-muted-color)" :font-size "0.85rem"}}
         "Session not connected."]]]

      :else
      [e/chat-area {}
       (for [message messages]
         (render-message message))
       (when awaiting-response?
         (render-loading-indicator))])))

(defn- render-composer-excerpts [excerpts]
  (when (seq excerpts)
    [:div.excerpt-list
     (for [{:keys [id text]} excerpts]
       [:span.excerpt-chip {:key id}
        "excerpt: " (truncate text composer-excerpt-cap)
        [:button.dismiss
         {:type "button"
          :on {:click [[:excerpt.actions/remove id]]}}
         "✕"]])
     (when (> (count excerpts) 1)
       [:button {:type "button"
                 :on {:click [[:excerpt.actions/clear-active]]}}
        "Clear excerpts"])]))

(defn- render-attachment-indicator [pending-attachments]
  (when (seq pending-attachments)
    [:div {:style {:margin-bottom "0.5rem"
                   :font-size "0.875rem"
                   :color "var(--pico-muted-color)"}}
     [:span "📎 " (count pending-attachments) " file" (when (> (count pending-attachments) 1) "s") " attached"]
     [:button {:type "button"
               :style {:margin-left "0.5rem"
                       :padding "0.125rem 0.5rem"
                       :font-size "0.75rem"}
               :on {:click [[:ui.actions/clear-pending-attachments]]}}
      "Clear"]]))

(defn render-input-form [{:keys [input-value loading? pending-attachments excerpts shell?]}]
  [:footer
   [:form {:on {:submit [[:effects/prevent-default]
                         [:form.actions/submit]]}}
    (render-composer-excerpts excerpts)
    (render-attachment-indicator pending-attachments)
    [:fieldset {:role "group"}
     [:textarea {:class "chat-input"
                 :rows 2
                 :value input-value
                 :placeholder (if shell? "Session not connected" "Type a message... (Shift+Enter for new line)")
                 :disabled (or loading? shell?)
                 :on {:input [[:form.actions/update-input [:event.target/value]]]
                      :keydown [[:form.actions/handle-submit-keys [:event/key-pressed]]]
                      :dragover [[:form.actions/handle-dragover]]
                      :drop [[:effects/prevent-default]
                             [:form.actions/handle-file-drop [:event/dropped-files]]]}
                 :autofocus true}]

     [:button {:type "submit"
               :disabled (or loading? shell? (str/blank? input-value))}
      "Send"]]]])
