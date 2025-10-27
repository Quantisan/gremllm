(ns gremllm.renderer.actions
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.actions.ui :as ui]        ; UI interactions
            [gremllm.renderer.actions.messages :as msg]  ; Message handling
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.renderer.actions.system :as system]
            [gremllm.renderer.actions.settings :as settings]
            [gremllm.renderer.state.ui :as ui-state]))

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

;; Register prevent-default as an effect
(nxr/register-effect! :effects/prevent-default
  (fn [{:keys [dispatch-data]} _]
    (some-> dispatch-data
            :replicant/dom-event
            (.preventDefault))))

;; TODO: refactor to be more Nexus-like
(nxr/register-effect! :topic.effects/handle-rename-keys
  (fn [{:keys [dispatch dispatch-data]} _ topic-id]
    (let [e (:replicant/dom-event dispatch-data)
          k (some-> e .-key)]
      (case k
        "Enter" (do (.preventDefault e)
                    (let [v (.. e -target -value)]
                      (dispatch [[:topic.actions/commit-rename topic-id v]])))
        "Escape" (do (.preventDefault e)
                     (dispatch [[:ui.actions/exit-topic-rename-mode topic-id]]))
        nil))))

(defn promise->actions [{:keys [dispatch]} _ {:keys [promise on-success on-error]}]
  (-> promise
      (.then (fn [result]
               (when on-success (dispatch (mapv #(conj % result) on-success)))))
      (.catch (fn [error]
                (when on-error (dispatch (mapv #(conj % error) on-error)))))))

;; Generic promise effect
(nxr/register-effect! :effects/promise promise->actions)

;; Console error effect
(nxr/register-effect! :ui.effects/console-error
  (fn [_ _ & args]
    (apply js/console.error args)))

;; UI
(nxr/register-action! :form.actions/update-input ui/update-input)
(nxr/register-action! :form.actions/handle-submit-keys ui/handle-submit-keys)
(nxr/register-action! :form.actions/submit ui/submit-messages)
(nxr/register-action! :ui.actions/show-settings ui/show-settings)
(nxr/register-action! :ui.actions/hide-settings ui/hide-settings)

;; Message
(nxr/register-action! :messages.actions/add-to-chat msg/add-message)
(nxr/register-action! :llm.actions/response-received msg/llm-response-received)
(nxr/register-action! :llm.actions/response-error msg/llm-response-error)

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
(nxr/register-action! :topic.actions/set-name topic/set-name)
(nxr/register-action! :topic.actions/update-model topic/update-model)
(nxr/register-action! :ui.actions/exit-topic-rename-mode
  (fn [_state _topic-id]
    [[:ui.effects/save ui-state/renaming-topic-id-path nil]]))
(nxr/register-action! :topic.actions/mark-unsaved topic/mark-unsaved)
(nxr/register-action! :topic.actions/mark-active-unsaved topic/mark-active-unsaved)
(nxr/register-action! :topic.actions/mark-saved topic/mark-saved)
(nxr/register-action! :topic.actions/auto-save topic/auto-save)
(nxr/register-action! :topic.actions/save-success topic/save-topic-success)
(nxr/register-action! :topic.actions/save-error topic/save-topic-error)

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

