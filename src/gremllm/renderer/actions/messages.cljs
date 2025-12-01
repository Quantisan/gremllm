(ns gremllm.renderer.actions.messages
  (:require [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.form :as form-state]))

(defn add-message [_state message]
  [[:messages.actions/append-to-state message]
   [:topic.actions/mark-active-unsaved]
   [:ui.actions/scroll-chat-to-bottom]
   ;; TODO: we should not save if the last message was an Error
   [:topic.actions/auto-save]])

(defn append-to-state [state message]
  (if-let [active-id (topic-state/get-active-topic-id state)]
    (let [path-to-messages (topic-state/topic-field-path active-id :messages)
          current-messages (topic-state/get-messages state)
          messages-to-save (conj (or current-messages []) message)]
      [[:effects/save path-to-messages messages-to-save]])
    (throw (js/Error. "Cannot append message: no active topic."))))

(defn llm-response-received [_state assistant-id response]
  (let [clj-response (js->clj response :keywordize-keys true)]
    [[:loading.actions/set-loading? assistant-id false]
     [:messages.actions/add-to-chat {:id   assistant-id
                                     :type :assistant
                                     :text (:text clj-response)}]]))

(defn llm-response-error [state assistant-id error]
  (js/console.error "Renderer received LLM error:"
                    (clj->js {:assistantId assistant-id
                              :errorMessage (.-message error)
                              :activeTopicId (topic-state/get-active-topic-id state)
                              :messageCount (count (topic-state/get-messages state))}))
  [[:loading.actions/set-loading? assistant-id false]
   [:llm.actions/set-error assistant-id
    (str "Failed to get response: " (or (.-message error) "Network error"))]])

;; Helper function to convert messages to API format
(defn messages->api-format [messages]
  (->> messages
       (map (fn [{:keys [type text]}]
              {:role (if (= type :user) "user" "assistant")
               :content text}))))

;; Action for sending messages to LLM
(defn send-messages [state assistant-id model]
  (let [messages (messages->api-format (topic-state/get-messages state))
        file-paths (when-let [attachments (seq (form-state/get-pending-attachments state))]
                     (mapv :path attachments))]
    [[:effects/send-llm-messages
      {:messages   messages
       :model      model
       :file-paths file-paths
       :on-success [[:llm.actions/response-received assistant-id]]
       :on-error   [[:llm.actions/response-error assistant-id]]}]]))

