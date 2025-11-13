(ns gremllm.renderer.actions.messages
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.form :as form-state]))

(defn add-message [_state message]
  [[:messages.actions/append-to-state message]
   [:topic.actions/mark-active-unsaved]
   [:ui.actions/scroll-chat-to-bottom]
   ;; TODO: we should not save if the last message was an Error
   [:topic.actions/auto-save]])

(defn append-to-state [state message]
  (if-let [active-id (topic-state/get-active-topic-id state)]
    (let [current-messages (topic-state/get-messages state)
          path-to-messages (topic-state/topic-field-path active-id :messages)]
      [[:effects/save path-to-messages (conj (or current-messages []) message)]])
    (throw (js/Error. "Cannot append message: no active topic."))))

;; Domain-specific actions
(nxr/register-action! :messages.actions/append-to-state append-to-state)

(nxr/register-action! :loading.actions/set-loading?
  (fn [_state id loading?]
    [[:effects/save (loading-state/loading-path id) loading?]]))

(nxr/register-action! :llm.actions/set-error
  (fn [_state assistant-id error-message]
    [[:effects/save (loading-state/assistant-errors-path assistant-id) error-message]]))

(nxr/register-action! :llm.actions/unset-all-errors
  (fn [_state]
    [[:effects/save [:assistant-errors] nil]]))

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

;; Effect handler for sending messages to LLM via IPC
(nxr/register-effect! :effects/send-llm-messages
  (fn [_ _store {:keys [messages model file-paths on-success on-error]}]
    ;; CHECKPOINT 1: Renderer sending to IPC
    (js/console.log "[CHECKPOINT 1] Renderer: Sending attachments to IPC"
                    (clj->js {:file-paths file-paths}))
    [[:effects/promise
      {:promise    (js/window.electronAPI.sendMessage
                     (clj->js messages)
                     model
                     (clj->js file-paths))
       :on-success on-success
       :on-error   on-error}]]))

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

