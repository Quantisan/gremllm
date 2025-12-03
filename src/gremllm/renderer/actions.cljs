(ns gremllm.renderer.actions
  (:require [nexus.registry :as nxr]
            [gremllm.schema :as schema]
            [gremllm.renderer.actions.ui :as ui]        ; UI interactions
            [gremllm.renderer.actions.messages :as msg]  ; Message handling
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.renderer.actions.system :as system]
            [gremllm.renderer.actions.settings :as settings]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.loading :as loading-state]))

;; Set up how to extract state from your atom
(nxr/register-system->state! deref)

;; TODO: refactor this namespace to separate actions and effects, following FCIS pattern

;; Register a basic save effect (similar to your assoc-in)
(nxr/register-effect! :effects/save
  (fn [_ store path value]
    (if (empty? path)
      (reset! store value)
      (swap! store assoc-in path value))))

;; Register the event.target/value placeholder you're already using
(nxr/register-placeholder! :event.target/value
  (fn [{:replicant/keys [dom-event]}]
    (some-> dom-event .-target .-value)))

;; Register placeholder for keyboard events
(nxr/register-placeholder! :event/key-pressed
  (fn [{:replicant/keys [dom-event]}]
    {:key (.-key dom-event)
     :shift? (.-shiftKey dom-event)}))

;; Register placeholder for drag-drop file events
(nxr/register-placeholder! :event/dropped-files
  (fn [{:replicant/keys [dom-event]}]
    (when-let [dt (.-dataTransfer dom-event)]
      (mapv (fn [file]
              {:name (.-name file)
               :size (.-size file)
               :type (.-type file)
               :path (js/window.electronAPI.getFilePath file)})
            (array-seq (.-files dt))))))

;; Register prevent-default as an effect
(nxr/register-effect! :effects/prevent-default
  (fn [{:keys [dispatch-data]} _]
    (some-> dispatch-data
            :replicant/dom-event
            (.preventDefault))))

(defn promise->actions [{:keys [dispatch]} _ {:keys [promise on-success on-error]}]
  (-> promise
      (.then (fn [result]
               (when on-success (dispatch (mapv #(conj % result) on-success)))))
      (.catch (fn [error]
                (when on-error (dispatch (mapv #(conj % error) on-error)))))))

;; Generic promise effect
(nxr/register-effect! :effects/promise promise->actions)

; DOM placeholders
(nxr/register-placeholder! :dom/element-by-id
  (fn [_ id]
    (js/document.getElementById id)))

(nxr/register-placeholder! :dom.element/property
  (fn [_ element prop]
    (when element (aget element prop))))

; Generic DOM effects
(nxr/register-effect! :effects/set-element-property
  (fn [_ _ {:keys [on-element set-property to-value]}]
    (when (and on-element to-value)
      (aset on-element set-property to-value))))

(nxr/register-effect! :effects/focus
  (fn [_ _ selector]
    (when-let [element (js/document.querySelector selector)]
      (.focus element))))

;; Console error effect
(nxr/register-effect! :ui.effects/console-error
  (fn [_ _ & args]
    (apply js/console.error args)))

;; Workspace reload effect
(nxr/register-effect! :workspace.effects/reload
  (fn [_ _]
    (.reloadWorkspace js/window.electronAPI)))

;; UI
(nxr/register-action! :form.actions/update-input ui/update-input)
(nxr/register-action! :form.actions/clear-input ui/clear-input)
(nxr/register-action! :form.actions/handle-submit-keys ui/handle-submit-keys)
(nxr/register-action! :form.actions/submit ui/submit-messages)
(nxr/register-action! :form.actions/handle-dragover ui/handle-dragover)
(nxr/register-action! :form.actions/handle-file-drop ui/handle-file-drop)
(nxr/register-action! :ui.actions/clear-pending-attachments ui/clear-pending-attachments)
(nxr/register-action! :ui.actions/show-settings ui/show-settings)
(nxr/register-action! :ui.actions/hide-settings ui/hide-settings)
(nxr/register-action! :ui.actions/scroll-chat-to-bottom ui/scroll-chat-to-bottom)
(nxr/register-action! :ui.actions/focus-chat-input ui/focus-chat-input)

;; Message
(nxr/register-action! :messages.actions/add-to-chat msg/add-message)
(nxr/register-action! :messages.actions/append-to-state msg/append-to-state)
(nxr/register-action! :llm.actions/response-received msg/llm-response-received)
(nxr/register-action! :llm.actions/response-error msg/llm-response-error)
(nxr/register-action! :llm.actions/send-messages msg/send-messages)

(nxr/register-action! :loading.actions/set-loading?
  (fn [_state id loading?]
    [[:effects/save (loading-state/loading-path id) loading?]]))

(nxr/register-action! :llm.actions/set-error
  (fn [_state assistant-id error-message]
    [[:effects/save (loading-state/assistant-errors-path assistant-id) error-message]]))

(nxr/register-action! :llm.actions/unset-all-errors
  (fn [_state]
    [[:effects/save [:assistant-errors] nil]]))

(nxr/register-effect! :effects/send-llm-messages
  (fn [{:keys [dispatch]} _store {:keys [messages model file-paths on-success on-error]}]
    (js/console.log "[CHECKPOINT 1] Renderer: Sending to IPC"
                    (clj->js {:messages messages
                              :messages-count (count messages)
                              :file-paths file-paths
                              :model model}))
    (dispatch
      [[:effects/promise
        {:promise    (js/window.electronAPI.sendMessage
                       (schema/messages-to-ipc messages)
                       (schema/model-to-ipc model)
                       (schema/attachment-paths-to-ipc file-paths))
         :on-success on-success
         :on-error   on-error}]])))

;; Workspace
(nxr/register-action! :workspace.actions/bootstrap workspace/bootstrap)
(nxr/register-action! :workspace.actions/opened workspace/opened)
(nxr/register-action! :workspace.actions/restore-with-topics workspace/restore-with-topics)
(nxr/register-action! :workspace.actions/initialize-empty workspace/initialize-empty)
(nxr/register-action! :workspace.actions/load-error workspace/load-error)
(nxr/register-action! :workspace.actions/mark-loaded workspace/mark-loaded)
(nxr/register-action! :workspace.actions/set workspace/set-workspace)
(nxr/register-action! :workspace.actions/pick-folder workspace/pick-folder)

;; Topic

;; Register all topic actions
(nxr/register-action! :topic.actions/start-new topic/start-new-topic)
(nxr/register-action! :topic.actions/set-active topic/set-active)
(nxr/register-action! :topic.actions/begin-rename topic/begin-rename)
(nxr/register-action! :topic.actions/commit-rename topic/commit-rename)
(nxr/register-action! :topic.actions/handle-rename-keys topic/handle-rename-keys)
(nxr/register-action! :topic.actions/set-name topic/set-name)
(nxr/register-action! :topic.actions/update-model topic/update-model)
(nxr/register-action! :ui.actions/exit-topic-rename-mode
  (fn [_state _topic-id]
    [[:effects/save ui-state/renaming-topic-id-path nil]]))
(nxr/register-action! :topic.actions/mark-unsaved topic/mark-unsaved)
(nxr/register-action! :topic.actions/mark-active-unsaved topic/mark-active-unsaved)
(nxr/register-action! :topic.actions/mark-saved topic/mark-saved)
(nxr/register-action! :topic.actions/auto-save topic/auto-save)
(nxr/register-action! :topic.actions/save-success topic/save-topic-success)
(nxr/register-action! :topic.actions/save-error topic/save-topic-error)
(nxr/register-action! :topic.actions/delete-success topic/delete-topic-success)
(nxr/register-action! :topic.actions/delete-error topic/delete-topic-error)

(nxr/register-action! :system.actions/request-info system/request-info)
(nxr/register-action! :system.actions/set-info system/set-info)
(nxr/register-action! :system.actions/request-error system/request-error)

;; Settings
(nxr/register-action! :settings.actions/update-input settings/update-input)
(nxr/register-action! :settings.actions/save-key settings/save-key)
(nxr/register-action! :settings.actions/remove-key settings/remove-key)
(nxr/register-action! :settings.actions/save-success settings/save-success)
(nxr/register-action! :settings.actions/save-error settings/save-error)
(nxr/register-action! :settings.actions/remove-success settings/remove-success)
(nxr/register-action! :settings.actions/remove-error settings/remove-error)

