(ns gremllm.renderer.actions.messages
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.loading :as loading-state]))

(defn add-message [_state message]
  [[:messages.actions/append-to-state message]
   [:topic.actions/mark-active-unsaved]
   [:effects/scroll-to-bottom "chat-messages-container"]
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
                                     :text (get-in clj-response [:content 0 :text])}]]))

(defn llm-response-error [_state assistant-id error]
  (js/console.error "LLM API Error:" error)
  [[:loading.actions/set-loading? assistant-id false]
   [:llm.actions/set-error assistant-id
    (str "Failed to get response: " (or (.-message error) "Network error"))]])

;; Helper function to convert messages to API format
(defn messages->api-format [messages]
  (->> messages
       (map (fn [{:keys [type text]}]
              {:role (if (= type :user) "user" "assistant")
               :content text}))))

;; Effect for sending messages to LLM
(nxr/register-effect! :llm.effects/send-llm-messages
  (fn [{dispatch :dispatch} store assistant-id model]
    (dispatch
      [[:effects/promise
        {:promise    (-> (topic-state/get-messages @store)
                         (messages->api-format)
                         (clj->js)
                         (js/window.electronAPI.sendMessage model))
         :on-success [[:llm.actions/response-received assistant-id]]
         :on-error   [[:llm.actions/response-error assistant-id]]}]])))

