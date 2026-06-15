(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [gremllm.schema :as schema]
            [gremllm.schema.codec.acp :as acp-codec]
            [gremllm.renderer.state.acp :as acp-state]
            [gremllm.renderer.state.loading :as loading-state]
            [gremllm.renderer.state.topic :as topic-state]))

(defn- continuing? [state message-type]
  (= message-type (:type (peek (topic-state/get-messages state)))))

(defn append-to-response
  "Appends chunk to the last assistant message (streaming continuation)."
  [state chunk-text]
  ;; TODO: check that acp-session-id of the chunk is same as that of the topic's
  (let [topic-id (topic-state/get-active-topic-id state)
        messages (topic-state/get-messages state)
        last-idx (dec (count messages))
        new-text (str (:text (peek messages)) chunk-text)
        msg-path (conj (topic-state/topic-field-path topic-id :messages) last-idx :text)]
    [:effects/save msg-path new-text]))

(defn append-streaming-text
  "Continues or starts a streaming assistant/reasoning message from a text chunk."
  [state update]
  (let [message-type (get acp-codec/acp-chunk->message-type (:session-update update))
        chunk-text   (acp-codec/acp-update-text update)]
    (if (continuing? state message-type)
      [(append-to-response state chunk-text)]
      (let [topic-id (topic-state/get-active-topic-id state)
            message  {:id   (schema/generate-message-id)
                      :type message-type
                      :text chunk-text}]
        [[:messages.actions/add-to-chat-no-save topic-id message]]))))

(defn start-web-search-message
  "Mints a pending :web-search :tool-call message from a WebSearch :tool-call event."
  [_state update]
  [[:tool-call.actions/start
    {:id               (schema/generate-message-id)
     :type             :tool-call
     :tool-call-id     (:tool-call-id update)
     :tool             :web-search
     :tool-call-status (or (:status update) "pending")
     :query            nil
     :text             ""}]])

(defn update-web-search-message
  "Patches an existing :web-search :tool-call message with status/query refinements
   from a WebSearch :tool-call-update event. Returns nil when the update carries
   no patchable fields."
  [_state update]
  (let [new-query (get-in update [:raw-input :query])
        patch     (cond-> {}
                    (:status update) (assoc :tool-call-status (:status update))
                    new-query        (assoc :query new-query))]
    (when (seq patch)
      [[:tool-call.actions/update (:tool-call-id update) patch]])))

(defn record-read-message
  "Mints a one-shot completed :read :tool-call message from a Read tool-call-update
   carrying file metadata. Read collapses start/complete into a single event."
  [_state update]
  [[:tool-call.actions/start
    {:id               (schema/generate-message-id)
     :type             :tool-call
     :tool-call-id     (:tool-call-id update)
     :tool             :read
     :tool-call-status "completed"
     :text             (acp-codec/acp-read-display-label update)}]])

(defn append-edit-diffs
  "Appends pending diffs extracted from an Edit/Write tool-call-update's content.
   Skips updates whose tool-call-id was already resolved via accept/reject — the
   propose-then-execute path stashes the diff at requestPermission time, then the
   SDK's PostToolUse hook re-emits the same diff after disk write completes."
  [state update]
  (let [topic-id     (topic-state/get-active-topic-id state)
        tool-call-id (:tool-call-id update)
        resolved     (topic-state/get-resolved-tool-calls state topic-id)]
    (when-not (contains? resolved tool-call-id)
      [[:topic.actions/append-pending-diffs (acp-codec/edit-diffs update)]])))

(defn session-update
  "Routes incoming ACP session updates to the matching chat-state operation.

  update: acp-codec/AcpUpdate"
  [state {:keys [update]}]
  (cond
    (acp-codec/streaming-text-chunk? update)
    (append-streaming-text state update)

    (acp-codec/web-search-started? update)
    (start-web-search-message state update)

    (acp-codec/web-search-updated? update)
    (update-web-search-message state update)

    (acp-codec/read-completed? update)
    (record-read-message state update)

    (acp-codec/edit-completed? update)
    (append-edit-diffs state update)))

(defn resume-or-new-session
  "Decide how to bring a topic's ACP session live: resume a persisted session
   id, create a new session, or nil when already live or init is in flight."
  [state topic-id]
  (when-not (or (acp-state/live? state topic-id)
                (loading-state/loading? state topic-id))
    (if-let [acp-session-id (topic-state/get-acp-session-id state topic-id)]
      [[:acp.actions/resume-session topic-id acp-session-id]]
      [[:acp.actions/new-session topic-id]])))

(defn session-ready
  "Session created/resumed successfully. Save acp-session-id and mark live."
  [state topic-id acp-session-id]
  (js/console.log "[ACP] Session ready:" acp-session-id "for topic:" topic-id)
  [[:loading.actions/set-loading? topic-id false]
   [:effects/save (topic-state/acp-session-id-path topic-id) acp-session-id]
   [:effects/save acp-state/live-topics-path (acp-state/with-topic-live state topic-id)]])

(defn session-error
  "ACP session initialization failed."
  [_state topic-id error]
  (js/console.error "[ACP] Session init failed:" error)
  [[:loading.actions/set-loading? topic-id false]])

(defn new-session [_state topic-id]
  [[:loading.actions/set-loading? topic-id true]
   [:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI)
     :on-success [[:acp.actions/session-ready topic-id]]
     :on-error   [[:acp.actions/session-error topic-id]]}]])

(defn resume-session
  "Resume existing ACP session for topic."
  [_state topic-id acp-session-id]
  [[:loading.actions/set-loading? topic-id true]
   [:effects/promise
    {:promise    (.acpResumeSession js/window.electronAPI acp-session-id)
     :on-success [[:acp.actions/session-ready topic-id]]
     :on-error   [[:acp.actions/session-error topic-id]]}]])

(defn prompt-succeeded
  [_state topic-id _result]
  [[:loading.actions/set-loading? topic-id false]
   [:topic.actions/finalize-turn topic-id]])

(defn prompt-failed
  [_state topic-id _error]
  [[:loading.actions/set-loading? topic-id false]])

(defn send-prompt [state message]
  (let [topic-id       (topic-state/get-active-topic-id state)
        acp-session-id (topic-state/get-acp-session-id state topic-id)]
    (if acp-session-id
      [[:loading.actions/set-loading? topic-id true]
       [:effects/promise
        {:promise    (.acpPrompt js/window.electronAPI
                                 acp-session-id
                                 (clj->js message))
         :on-success [[:acp.actions/prompt-succeeded topic-id]]
         :on-error   [[:acp.actions/prompt-failed topic-id]]}]]
      (js/console.error "[ACP] No session for prompt"))))
