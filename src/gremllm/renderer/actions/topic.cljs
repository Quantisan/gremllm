(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [clojure.string :as str]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.schema :as schema]))

(defn set-topic [_state topic-js]
  (when topic-js
    (let [topic (schema/topic-from-ipc topic-js)
          topic-id (:id topic)]
      [[:effects/save (conj topic-state/topics-path topic-id) topic]
       [:effects/save topic-state/active-topic-id-path topic-id]])))

(defn start-new-topic [_state]
  (let [new-topic (schema/create-topic)]
    [[:effects/save (conj topic-state/topics-path (:id new-topic)) new-topic]
     [:effects/save topic-state/active-topic-id-path (:id new-topic)]]))

(defn mark-saved [_state topic-id]
  [[:effects/save (topic-state/topic-field-path topic-id :unsaved?) false]])

(defn mark-unsaved [_state topic-id]
  [[:effects/save (topic-state/topic-field-path topic-id :unsaved?) true]])

(defn set-name [_state topic-id new-name]
  [[:effects/save (topic-state/topic-field-path topic-id :name) new-name]])

(defn save-topic-success [_state topic-id filepath]
  ;; TODO: UI notification
  (js/console.log "Topic" topic-id "saved to:" filepath)
  [[:topic.actions/mark-saved topic-id]])

(defn save-topic-error [_state topic-id error]
  (js/console.error "save-topic (topic-id: " topic-id ") failed:" error)
  [])

(defn mark-active-unsaved [state]
  (let [active-id (topic-state/get-active-topic-id state)]
    [[:topic.actions/mark-unsaved active-id]]))

(defn switch-topic [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path topic-id]])

(defn begin-rename [state topic-id]
  ;; Enter inline rename mode for this topic
  (when (get-in state (topic-state/topic-field-path topic-id :name))
    [[:effects/save ui-state/renaming-topic-id-path topic-id]]))

(defn commit-rename [state topic-id new-name]
  (let [new-name (-> (or new-name "") str/trim)
        current  (get-in state (topic-state/topic-field-path topic-id :name))]
    (cond
      (str/blank? new-name)
      [[:ui.actions/exit-topic-rename-mode topic-id]]

      (= new-name current)
      [[:ui.actions/exit-topic-rename-mode topic-id]]

      :else
      [[:topic.actions/set-name topic-id new-name]
       [:topic.actions/mark-unsaved topic-id]
       [:ui.actions/exit-topic-rename-mode topic-id]])))

(nxr/register-effect! :topic.effects/save-active-topic
  (fn [{dispatch :dispatch} store]
    (let [active-topic   (topic-state/get-active-topic @store)]
      (if-let [topic-id (:id active-topic)]
        (dispatch
         [[:effects/promise
           {:promise    (.saveTopic js/window.electronAPI (clj->js active-topic))
            :on-success [[:topic.actions/save-success topic-id]]
            :on-error   [[:topic.actions/save-error topic-id]]}]])
        (dispatch [[:topic.actions/save-error nil (js/Error. "No active topic to save")]])))))

