(ns gremllm.renderer.actions
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.actions.ui :as ui]        ; UI interactions
            [gremllm.renderer.actions.messages :as msg]  ; Message handling
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.actions.system :as system]
            [gremllm.renderer.actions.settings :as settings]))

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

;; Register prevent-default as an effect
(nxr/register-effect! :effects/prevent-default
  (fn [{:keys [dispatch-data]} _]
    (some-> dispatch-data
            :replicant/dom-event
            (.preventDefault))))


(defn promise->actions [{:keys [dispatch]} _ {:keys [promise on-success on-error]}]
  (-> promise
      (.then (fn [result]
               (when on-success (dispatch [(conj on-success result)]))))
      (.catch (fn [error]
                (when on-error (dispatch [(conj on-error error)]))))))

;; Generic promise effect
(nxr/register-effect! :effects/promise promise->actions)

;; Console error effect
(nxr/register-effect! :ui.effects/console-error
  (fn [_ _ & args]
    (apply js/console.error args)))

;; UI
(nxr/register-action! :form.actions/update-input ui/update-input)
(nxr/register-action! :form.actions/submit ui/submit-messages)
(nxr/register-action! :ui.actions/show-settings ui/show-settings)
(nxr/register-action! :ui.actions/hide-settings ui/hide-settings)

;; Message
(nxr/register-action! :messages.actions/add-to-chat msg/add-message)
(nxr/register-action! :llm.actions/response-received msg/llm-response-received)
(nxr/register-action! :llm.actions/response-error msg/llm-response-error)

;; Topic

;; Register all topic actions
(nxr/register-action! :topic.actions/set topic/set-topic)
(nxr/register-action! :topic.actions/bootstrap topic/bootstrap)
(nxr/register-action! :topic.actions/restore-or-create-topic topic/restore-or-create-topic)
(nxr/register-action! :topic.actions/start-new topic/start-new-topic)
(nxr/register-action! :topic.actions/load-error topic/load-topic-error)
(nxr/register-action! :topic.actions/save-success topic/save-topic-success)
(nxr/register-action! :topic.actions/save-error topic/save-topic-error)

(nxr/register-action! :system.actions/request-info system/request-info)
(nxr/register-action! :system.actions/set-info system/set-info)
(nxr/register-action! :system.actions/request-error system/request-error)

;; Settings
(nxr/register-action! :settings.actions/update-api-key-input settings/update-api-key-input)
(nxr/register-action! :settings.actions/save-key settings/save-key)
(nxr/register-action! :settings.actions/remove-key settings/remove-key)
(nxr/register-action! :settings.actions/save-success settings/save-success)
(nxr/register-action! :settings.actions/save-error settings/save-error)
(nxr/register-action! :settings.actions/remove-success settings/remove-success)
(nxr/register-action! :settings.actions/remove-error settings/remove-error)

