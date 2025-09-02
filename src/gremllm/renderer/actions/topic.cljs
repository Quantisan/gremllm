(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.schema :as schema]))

(defn set-topic [_state topic-js]
  (when topic-js
    (let [clj-topic (js->clj topic-js :keywordize-keys true)
          normalized-topic (schema/topic-from-ipc clj-topic)
          topic-id (:id normalized-topic)]
      [[:effects/save (conj topic-state/topics-path topic-id) normalized-topic]
       [:effects/save topic-state/active-topic-id-path topic-id]])))

(defn start-new-topic [_state]
  (let [new-topic (schema/create-topic)]
    [[:effects/save (conj topic-state/topics-path (:id new-topic)) new-topic]
     [:effects/save topic-state/active-topic-id-path (:id new-topic)]]))

(defn mark-saved [_state topic-id]
  [[:effects/save (topic-state/topic-field-path topic-id :unsaved?) false]])

(defn save-topic-success [_state topic-id filepath]
  ;; TODO: UI notification
  (js/console.log "Topic" topic-id "saved to:" filepath)
  [[:topic.actions/mark-saved topic-id]])

(defn save-topic-error [_state topic-id error]
  (js/console.error "save-topic (topic-id: " topic-id ") failed:" error)
  [])

(defn mark-active-unsaved [state]
  (let [active-id (topic-state/get-active-topic-id state)]
    [[:effects/save (topic-state/topic-field-path active-id :unsaved?) true]]))

(defn switch-topic [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path (keyword topic-id)]])

(nxr/register-effect! :topic.effects/list
  (fn [{dispatch :dispatch} _store & [opts]]
   (dispatch
     [[:effects/promise
       {:promise    (.listTopics js/window.electronAPI)
        :on-success (:on-success opts)
        :on-error   (:on-error opts)}]])))

(nxr/register-effect! :topic.effects/save-active-topic
  (fn [{dispatch :dispatch} store]
    (let [active-topic (topic-state/get-active-topic @store)]
      (if-let [topic-id (:id active-topic)]
        (dispatch
         [[:effects/promise
           {:promise    (.saveTopic js/window.electronAPI (clj->js active-topic))
            :on-success [[:topic.actions/save-success topic-id]]
            :on-error   [[:topic.actions/save-error topic-id]]}]])
        (dispatch [[:topic.actions/save-error nil (js/Error. "No active topic to save")]])))))

