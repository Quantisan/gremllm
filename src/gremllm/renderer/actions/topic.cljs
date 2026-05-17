(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [clojure.string :as str]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]))

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
  [state topic-id]
  (let [messages (when topic-id
                   (topic-state/get-topic-field state topic-id :messages))
        excerpts (when topic-id
                   (topic-state/get-topic-field state topic-id :excerpts))]
    (when (or (seq messages) (seq excerpts))
      [[:topic.effects/save-topic topic-id]])))

(defn finalize-turn
  "Prompt success workflow: consume excerpts used by the turn and persist
   the topic after streamed assistant updates have landed in state."
  [_state topic-id]
  [[:excerpt.actions/consume topic-id]
   [:topic.actions/mark-unsaved topic-id]
   [:topic.effects/auto-save topic-id]])

(defn append-pending-diffs [state diffs]
  ;; TODO: incoming diffs should be matched with acp-session-id
  (let [topic-id (topic-state/get-active-topic-id state)
        existing (or (get-in state (topic-state/pending-diffs-path topic-id)) [])]
    [[:effects/save (topic-state/pending-diffs-path topic-id) (into existing diffs)]]))

(defn append-pending-permission
  "Add the diffs from an enriched ACP permission request to the active topic's
   pending-diffs, tagging each with :tool-call-id so accept/reject can resolve
   the right pending Promise. Returns nil when the permission carries no diffs."
  [state enriched]
  (let [{:keys [tool-call-id content]} (:tool-call enriched)
        diffs (when content (filterv #(= "diff" (:type %)) content))]
    (when (seq diffs)
      (let [topic-id (topic-state/get-active-topic-id state)
            existing (or (get-in state (topic-state/pending-diffs-path topic-id)) [])
            tagged   (mapv #(assoc % :tool-call-id tool-call-id) diffs)]
        [[:effects/save (topic-state/pending-diffs-path topic-id) (into existing tagged)]]))))

(defn- without-tool-call [pending-diffs tool-call-id]
  (filterv #(not= tool-call-id (:tool-call-id %)) pending-diffs))

(defn- resolve-diff-actions [state tool-call-id option-id]
  (let [topic-id (topic-state/get-active-topic-id state)
        existing (or (get-in state (topic-state/pending-diffs-path topic-id)) [])
        resolved (topic-state/get-resolved-tool-calls state topic-id)]
    [[:acp.effects/resolve-permission tool-call-id option-id]
     [:effects/save
      (topic-state/pending-diffs-path topic-id)
      (without-tool-call existing tool-call-id)]
     [:effects/save
      (topic-state/resolved-tool-calls-path topic-id)
      (conj resolved tool-call-id)]]))

(defn accept-diff
  "User accepted a proposed diff. Resolves the SDK's pending permission as
   allow_once, drops matching entries from :pending-diffs, and records
   tool-call-id in :resolved-tool-calls so any duplicate PostToolUse update
   is suppressed by append-edit-diffs."
  [state tool-call-id]
  (resolve-diff-actions state tool-call-id "allow_once"))

(defn reject-diff
  "User rejected a proposed diff. Resolves the SDK's pending permission as
   reject_once, drops matching entries from :pending-diffs, and records
   tool-call-id in :resolved-tool-calls."
  [state tool-call-id]
  (resolve-diff-actions state tool-call-id "reject_once"))

(defn set-active
  "Set the active topic and initialize its ACP session."
  [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path topic-id]
   [:acp.effects/init-session topic-id]])

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
       [:topic.effects/auto-save topic-id]])))

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
           {:promise    (.saveTopic js/window.electronAPI (codec/topic-to-ipc topic))
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
