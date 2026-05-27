(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]
            [gremllm.renderer.state.excerpt :as excerpt-state]
            [gremllm.renderer.actions.excerpt :as excerpt]
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

(defn save-topic-success [_state topic-id filepath]
  ;; TODO: UI notification
  (js/console.log "Topic" topic-id "saved to:" filepath)
  [[:topic.actions/mark-saved topic-id]])

(defn save-topic-error [_state topic-id error]
  (js/console.error "save-topic (topic-id: " topic-id ") failed:" error)
  [])

(defn delete-topic-success [_state _topic-id]
  ;; Reload document from disk to sync state
  [[:document.effects/reload]])

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
  ;; TODO (inbound-routing): see state/topic.cljs acp-session-id-path
  (let [topic-id (topic-state/get-active-topic-id state)
        existing (or (get-in state (topic-state/pending-diffs-path topic-id)) [])]
    [[:effects/save (topic-state/pending-diffs-path topic-id) (into existing diffs)]]))

(defn append-pending-permission
  "Records an ACP permission request against the active topic: tags each diff
   in :content with :tool-call-id (so accept/reject can resolve the right
   pending Promise) and stashes the request's :options under
   :pending-permission-options keyed by :tool-call-id (so accept/reject can
   map ACP :kind to the agent-defined :option-id). No-op when no diffs."
  [state enriched]
  ;; TODO (inbound-routing): see state/topic.cljs acp-session-id-path
  (let [{:keys [tool-call-id content]} (:tool-call enriched)
        diffs (when content (filterv #(= "diff" (:type %)) content))]
    (when (seq diffs)
      (let [topic-id (topic-state/get-active-topic-id state)
            existing (or (get-in state (topic-state/pending-diffs-path topic-id)) [])
            tagged   (mapv #(assoc % :tool-call-id tool-call-id) diffs)
            options-map (or (get-in state (topic-state/pending-permission-options-path topic-id)) {})]
        [[:effects/save (topic-state/pending-diffs-path topic-id) (into existing tagged)]
         [:effects/save (topic-state/pending-permission-options-path topic-id)
          (assoc options-map tool-call-id (:options enriched))]]))))

(defn- without-tool-call [pending-diffs tool-call-id]
  (filterv #(not= tool-call-id (:tool-call-id %)) pending-diffs))

(defn- option-id-for-kind
  "Find the :option-id of the entry in options whose :kind matches kind. nil if none."
  [options kind]
  (some #(when (= kind (:kind %)) (:option-id %)) options))

(defn- resolve-diff-actions [state tool-call-id kind]
  (let [topic-id    (topic-state/get-active-topic-id state)
        existing    (or (get-in state (topic-state/pending-diffs-path topic-id)) [])
        options-map (or (get-in state (topic-state/pending-permission-options-path topic-id)) {})
        options     (get options-map tool-call-id)
        option-id   (option-id-for-kind options kind)
        cleanup     [[:effects/save
                      (topic-state/pending-diffs-path topic-id)
                      (without-tool-call existing tool-call-id)]
                     [:effects/save
                      (topic-state/pending-permission-options-path topic-id)
                      (dissoc options-map tool-call-id)]
                     [:effects/save
                      (topic-state/resolved-tool-calls-path topic-id)
                      (conj (topic-state/get-resolved-tool-calls state topic-id) tool-call-id)]]]
    (if option-id
      (into [[:acp.effects/resolve-permission tool-call-id option-id]] cleanup)
      (do
        (js/console.error "No option-id found for kind" kind "on tool-call" tool-call-id
                          "— available options:" (pr-str options))
        cleanup))))

(defn accept-diff
  "User accepted a proposed diff. Looks up the option-id for ACP kind
   \"allow_once\" from the stashed options, resolves the SDK's pending
   permission with that option-id, clears the pending-diffs/options entries,
   and records tool-call-id in :resolved-tool-calls so any duplicate
   PostToolUse update is suppressed by append-edit-diffs."
  [state tool-call-id]
  (resolve-diff-actions state tool-call-id "allow_once"))

(defn reject-diff
  "User rejected a proposed diff. Looks up the option-id for ACP kind
   \"reject_once\" from the stashed options, resolves the SDK's pending
   permission with that option-id, clears the pending-diffs/options entries,
   and records tool-call-id in :resolved-tool-calls."
  [state tool-call-id]
  (resolve-diff-actions state tool-call-id "reject_once"))

(defn start-from-selection
  "Create a new shell session anchored to the given excerpt."
  [_state anchor]
  (let [new-topic (assoc (schema/create-topic) :anchor anchor)
        topic-id  (:id new-topic)]
    [[:effects/save (topic-state/topic-path topic-id) new-topic]
     [:topic.actions/set-active topic-id]
     [:excerpt.actions/dismiss-popover]]))

(defn start-session-from-capture
  "Build an anchor from the current excerpt capture state, then create a session."
  [state]
  (let [captured (excerpt-state/get-captured state)
        locator-hints (excerpt-state/get-locator-hints state)]
    (when (and captured locator-hints)
      (let [anchor (excerpt/capture->excerpt captured locator-hints
                                             (str "excerpt-" (random-uuid)))]
        (start-from-selection state anchor)))))

(defn set-active
  "Set the active topic. ACP session init is handled separately."
  [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path topic-id]])

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
