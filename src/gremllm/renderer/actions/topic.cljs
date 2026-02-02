(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [clojure.string :as str]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.schema :as schema]))

(defn start-new-topic [_state]
  (let [new-topic (schema/create-topic)
        topic-id  (:id new-topic)]
    [[:effects/save (topic-state/topic-path topic-id) new-topic]
     [:topic.actions/set-active topic-id]]))

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

(defn delete-topic-success [_state _topic-id]
  ;; Reload workspace from disk to sync state
  [[:workspace.effects/reload]])

(defn delete-topic-error [_state topic-id error]
  (js/console.error "delete-topic (topic-id: " topic-id ") failed:" error)
  [])

(defn mark-active-unsaved [state]
  (let [active-id (topic-state/get-active-topic-id state)]
    [[:topic.actions/mark-unsaved active-id]]))

(defn auto-save
  ([state]
   (auto-save state (topic-state/get-active-topic-id state)))
  ([state topic-id]
   (when (-> (topic-state/get-topic state topic-id)
             (:messages)
             (seq))
     [[:topic.effects/save-topic topic-id]])))

(defn set-active
  "Set the active topic and initialize its ACP session."
  [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path topic-id]
   [:acp.actions/init-session topic-id]])

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

(defn handle-rename-keys [_state topic-id {:keys [key]} value]
  (case key
    "Enter"  [[:effects/prevent-default]
              [:topic.actions/commit-rename topic-id value]]
    "Escape" [[:effects/prevent-default]
              [:ui.actions/exit-topic-rename-mode topic-id]]
    nil))

;; Generic topic save effect - accepts any topic-id
(nxr/register-effect! :topic.effects/save-topic
  (fn [{dispatch :dispatch} store topic-id]
    (if-let [topic (topic-state/get-topic @store topic-id)]
      (dispatch
       [[:effects/promise
         {:promise    (.saveTopic js/window.electronAPI (schema/topic-to-ipc topic))
          :on-success [[:topic.actions/save-success topic-id]]
          :on-error   [[:topic.actions/save-error topic-id]]}]])
      (dispatch [[:topic.actions/save-error topic-id (js/Error. (str "Topic not found: " topic-id))]]))))

;; TODO: should :topic.effects/save-active-topic be an action?
;;
;; Convenience effect for saving the active topic
(nxr/register-effect! :topic.effects/save-active-topic
  (fn [{dispatch :dispatch} store]
    (if-let [topic-id (topic-state/get-active-topic-id @store)]
      (dispatch [[:topic.effects/save-topic topic-id]])
      (dispatch [[:topic.actions/save-error nil (js/Error. "No active topic to save")]]))))

;; TODO: should :topic.effects/delete-topic be an action?
;;
;; Delete topic effect - shows confirmation dialog and deletes file
(nxr/register-effect! :topic.effects/delete-topic
  (fn [{dispatch :dispatch} _store topic-id]
    (dispatch
     [[:effects/promise
       {:promise    (.deleteTopic js/window.electronAPI topic-id)
        :on-success [[:topic.actions/delete-success topic-id]]
        :on-error   [[:topic.actions/delete-error topic-id]]}]])))

