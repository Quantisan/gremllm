(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [clojure.string :as str]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.schema :as schema]))

(defn start-new-topic [_state]
  (let [new-topic     (schema/create-topic)
        topic-id      (:id new-topic)
        default-model (:model new-topic)]
    [[:effects/save (topic-state/topic-path topic-id) new-topic]
     [:topic.actions/set-active topic-id default-model]]))

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

(defn auto-save
  ([state]
   (auto-save state (topic-state/get-active-topic-id state)))
  ([state topic-id]
   (let [topic (topic-state/get-topic state topic-id)
         selected-model (form-state/get-selected-model state)]
     (js/console.log "[DIAGNOSTIC] auto-save called for topic-id:" topic-id
                     "| topic model:" (:model topic)
                     "| form selected model:" selected-model
                     "| topic data:" (clj->js topic))
     (when (-> topic (:messages) (seq))
       [[:topic.effects/save-topic topic-id]]))))

(defn set-active
  "Set the active topic. Optionally accepts model to avoid reading from state."
  ([state topic-id]
   (set-active state topic-id (topic-state/get-topic-field state topic-id :model)))
  ([_state topic-id model]
   [[:effects/save topic-state/active-topic-id-path topic-id]
    [:form.effects/update-model model]]))

(defn begin-rename [state topic-id]
  ;; Enter inline rename mode for this topic
  (when (topic-state/get-topic-field state topic-id :name)
    [[:effects/save ui-state/renaming-topic-id-path topic-id]]))

(defn commit-rename [state topic-id new-name]
  (let [new-name (-> (or new-name "") str/trim)
        current  (topic-state/get-topic-field state topic-id :name)]
    (cond
      (str/blank? new-name)
      [[:ui.actions/exit-topic-rename-mode topic-id]]

      (= new-name current)
      [[:ui.actions/exit-topic-rename-mode topic-id]]

      :else
      [[:topic.actions/set-name topic-id new-name]
       [:ui.actions/exit-topic-rename-mode topic-id]
       [:topic.actions/auto-save topic-id]])))

;; Generic topic save effect - accepts any topic-id
(nxr/register-effect! :topic.effects/save-topic
  (fn [{dispatch :dispatch} store topic-id]
    (if-let [topic (topic-state/get-topic @store topic-id)]
      (let [topic-js (clj->js topic)]
        (js/console.log "[DIAGNOSTIC] :topic.effects/save-topic - sending to IPC | topic-id:" topic-id "| topic data:" topic-js)
        (dispatch
         [[:effects/promise
           {:promise    (.saveTopic js/window.electronAPI topic-js)
            :on-success [[:topic.actions/save-success topic-id]]
            :on-error   [[:topic.actions/save-error topic-id]]}]]))
      (dispatch [[:topic.actions/save-error topic-id (js/Error. (str "Topic not found: " topic-id))]]))))

;; Convenience effect for saving the active topic
(nxr/register-effect! :topic.effects/save-active-topic
  (fn [{dispatch :dispatch} store]
    (if-let [topic-id (topic-state/get-active-topic-id @store)]
      (dispatch [[:topic.effects/save-topic topic-id]])
      (dispatch [[:topic.actions/save-error nil (js/Error. "No active topic to save")]]))))

