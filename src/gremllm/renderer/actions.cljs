(ns gremllm.renderer.actions
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.actions.ui :as ui]        ; UI interactions
            [gremllm.renderer.actions.messages :as msg]  ; Message handling
            [gremllm.renderer.actions.topic :as topic]
            [gremllm.renderer.actions.workspace :as workspace]
            [gremllm.renderer.actions.document :as document]
            [gremllm.renderer.actions.acp :as acp]
            [gremllm.renderer.actions.excerpt :as excerpt]
            [gremllm.schema.codec :as codec]
            [gremllm.renderer.ui.document.locator :as locator]
            [gremllm.renderer.state.ui :as ui-state]
            [gremllm.renderer.state.topic :as topic-state]
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

(nxr/register-effect! :effects/stop-propagation
  (fn [{:keys [dispatch-data]} _]
    (some-> dispatch-data
            :replicant/dom-event
            (.stopPropagation))))

(defn promise->actions [{:keys [dispatch]} _ {:keys [promise on-success on-error]}]
  (-> promise
      (.then (fn [result]
               (when on-success (dispatch (mapv #(conj % result) on-success)))))
      (.catch (fn [error]
                (when on-error (dispatch (mapv #(conj % error) on-error)))))))

(defn- active-text-selection
  "Returns the current user selection only when excerpt capture has non-empty text to stage."
  []
  (let [selection (js/document.getSelection)]
    (when (and selection
               (pos? (.-rangeCount selection))
               (not (.-isCollapsed selection)))
      selection)))

(defn- selection-locator-hints
  "Builds advisory document anchors for a staged excerpt and warns when the selection cannot be block-located."
  [article selection]
  (let [locator-hints (some-> article
                              (locator/selection-locator-from-dom selection))]
    (when (and article (nil? locator-hints))
      (js/console.warn "[excerpt] Selection has no block-anchored locator; popover suppressed."))
    locator-hints))

;; Generic promise effect
(nxr/register-effect! :effects/promise promise->actions)

;; Register placeholder for text selection events.
;; Returns {:selection CapturedSelection :anchor AnchorContext-or-nil :locator-hints LocatorHints-or-nil}
;; when a non-collapsed selection exists, nil otherwise.
;; locator-hints is nil when selection endpoints lack block-selector ancestors;
;; excerpt/capture treats that as a non-stageable selection.
(nxr/register-placeholder! :event/text-selection
  (fn [{:replicant/keys [dom-event]}]
    (when-let [selection (active-text-selection)]
      (let [panel         (some-> dom-event .-target (.closest ".document-panel"))
            article       (some-> panel (.querySelector "article"))
            locator-hints (selection-locator-hints article selection)]
        {:selection     (codec/captured-selection-from-dom selection)
         :anchor        (some-> panel codec/anchor-context-from-dom)
         :locator-hints locator-hints}))))

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
(nxr/register-action! :ui.actions/scroll-chat-to-bottom ui/scroll-chat-to-bottom)
(nxr/register-action! :ui.actions/focus-chat-input ui/focus-chat-input)
(nxr/register-action! :ui.actions/toggle-nav ui/toggle-nav)

;; Message
(nxr/register-action! :messages.actions/add-to-chat msg/add-message)
(nxr/register-action! :messages.actions/add-to-chat-no-save msg/add-message-no-save)
(nxr/register-action! :messages.actions/append-to-state msg/append-to-state)

(nxr/register-action! :loading.actions/set-loading?
  (fn [_state topic-id loading?]
    [[:effects/save (loading-state/loading-path topic-id) loading?]]))

;; Workspace
(nxr/register-action! :workspace.actions/opened workspace/opened)
(nxr/register-action! :workspace.actions/restore-with-topics workspace/restore-with-topics)
(nxr/register-action! :workspace.actions/initialize-empty workspace/initialize-empty)
(nxr/register-action! :workspace.actions/load-error workspace/load-error)
(nxr/register-action! :workspace.actions/mark-loaded workspace/mark-loaded)
(nxr/register-action! :workspace.actions/set workspace/set-workspace)
(nxr/register-action! :workspace.actions/pick-folder workspace/pick-folder)

;; Document
(nxr/register-action! :document.actions/create document/create)
(nxr/register-action! :document.actions/create-success document/create-success)
(nxr/register-action! :document.actions/create-error document/create-error)
(nxr/register-action! :document.actions/set-content document/set-content)

;; Topic

;; Register all topic actions
(nxr/register-action! :topic.actions/append-pending-diffs topic/append-pending-diffs)
(nxr/register-action! :topic.actions/start-new topic/start-new-topic)
(nxr/register-action! :topic.actions/set-active topic/set-active)
(nxr/register-action! :topic.actions/begin-rename topic/begin-rename)
(nxr/register-action! :topic.actions/commit-rename topic/commit-rename)
(nxr/register-action! :topic.actions/handle-rename-keys topic/handle-rename-keys)
(nxr/register-action! :topic.actions/set-name topic/set-name)
(nxr/register-action! :ui.actions/exit-topic-rename-mode
  (fn [_state _topic-id]
    [[:effects/save ui-state/renaming-topic-id-path nil]]))
(nxr/register-action! :topic.actions/mark-unsaved topic/mark-unsaved)
(nxr/register-action! :topic.actions/mark-active-unsaved topic/mark-active-unsaved)
(nxr/register-action! :topic.actions/mark-saved topic/mark-saved)
(nxr/register-action! :topic.actions/save-success topic/save-topic-success)
(nxr/register-action! :topic.actions/save-error topic/save-topic-error)
(nxr/register-action! :topic.actions/delete-success topic/delete-topic-success)
(nxr/register-action! :topic.actions/delete-error topic/delete-topic-error)
(nxr/register-action! :topic.actions/finalize-turn topic/finalize-turn)

;; Auto-save effect - reads live state to check if messages exist before saving.
;; This must be an effect (not an action) to avoid stale state when called from async promises.
;; Actions receive immutable snapshots from dispatch time, but effects can @store for current state.
(nxr/register-effect! :topic.effects/auto-save
  (fn [{:keys [dispatch]} store topic-id]
    (when-let [effects (topic/auto-save @store topic-id)]
      (dispatch effects))))

;; Excerpt
(nxr/register-action! :excerpt.actions/capture excerpt/capture)
(nxr/register-action! :excerpt.actions/dismiss-popover excerpt/dismiss-popover)
(nxr/register-action! :excerpt.actions/add excerpt/add)
(nxr/register-action! :excerpt.actions/remove excerpt/remove-excerpt)
(nxr/register-action! :excerpt.actions/clear-active excerpt/clear-active)
(nxr/register-action! :excerpt.actions/consume excerpt/consume)
(nxr/register-action! :excerpt.actions/invalidate-across-topics excerpt/invalidate-across-topics)

;; ACP
(nxr/register-action! :acp.actions/new-session acp/new-session)
(nxr/register-action! :acp.actions/resume-session acp/resume-session)
(nxr/register-action! :acp.actions/send-prompt acp/send-prompt)
(nxr/register-action! :acp.actions/prompt-succeeded acp/prompt-succeeded)
(nxr/register-action! :acp.actions/prompt-failed acp/prompt-failed)
(nxr/register-action! :acp.actions/session-ready acp/session-ready)
(nxr/register-action! :acp.actions/session-error acp/session-error)
(nxr/register-action! :acp.events/session-update acp/session-update)

;; Accidental impurity: This routing logic is conceptually pure (state in, action out),
;; but must be an effect to see live store state. Actions receive immutable snapshots
;; captured at dispatch start, missing topic data saved by earlier effects in the chain.
(nxr/register-effect! :acp.effects/init-session
  (fn [{:keys [dispatch]} store topic-id]
    (if-let [existing-acp-session-id (topic-state/get-acp-session-id @store topic-id)]
      (dispatch [[:acp.actions/resume-session topic-id existing-acp-session-id]])
      (dispatch [[:acp.actions/new-session topic-id]]))))
