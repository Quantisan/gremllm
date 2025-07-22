(ns gremllm.renderer.actions.messages
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.loading :as loading-state]))

(defn add-message [_state message]
  [[:message.effects/add message]
   [:effects/scroll-to-bottom "chat-messages-container"]])

;; Domain-specific effects
(nxr/register-effect! :message.effects/add
  (fn [_ store message]
    (swap! store update-in (conj topic-state/path :messages) (fnil conj []) message)))

(nxr/register-effect! :loading.effects/set-loading?
  (fn [_ store id loading?]
    (swap! store assoc-in (loading-state/loading-path id) loading?)))

(nxr/register-effect! :llm.effects/set-error
  (fn [_ store assistant-id error-message]
    (swap! store assoc-in (loading-state/assistant-errors-path assistant-id) error-message)))

(nxr/register-effect! :llm.effects/unset-all-errors
  (fn [_ store]
    (swap! store assoc :assistant-errors nil)))

(defn llm-response-received [_state assistant-id response]
  (let [clj-response (js->clj response :keywordize-keys true)]
    [[:loading.effects/set-loading? assistant-id false]
     [:msg.actions/add {:id   assistant-id
                        :type :assistant
                        :text (get-in clj-response [:content 0 :text])}]]))

(defn llm-response-error [_state assistant-id error]
  (js/console.error "LLM API Error:" error)
  [[:loading.effects/set-loading? assistant-id false]
   [:llm.effects/set-error assistant-id
    (str "Failed to get response: " (or (.-message error) "Network error"))]])

;; Helper function to convert messages to API format
(defn messages->api-format [messages]
  (->> messages
       (map (fn [{:keys [type text]}]
              {:role (if (= type :user) "user" "assistant")
               :content text}))))

;; Effect for sending messages to LLM
(nxr/register-effect! :llm.effects/send-llm-messages
  (fn [{dispatch :dispatch} store assistant-id]
    (dispatch
      [[:effects/promise
        {:promise    (-> (topic-state/get-messages @store)
                         (messages->api-format)
                         (clj->js)
                         (js/window.electronAPI.sendMessage))
         :on-success [:llm.actions/response-received assistant-id]
         :on-error   [:llm.actions/response-error assistant-id]}]])))

