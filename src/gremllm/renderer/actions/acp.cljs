(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [malli.core :as m]
            [gremllm.schema :as schema]
            [gremllm.schema.codec.acp :as acp-codec]
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

(defn- start-response
  "Creates a new assistant message (first chunk of a new turn)."
  [topic-id message-type chunk-text message-id]
  (let [message {:id message-id :type message-type :text chunk-text}]
    (when-not (m/validate schema/Message message)
      (throw (js/Error. (str "Invalid Message: " (pr-str (m/explain schema/Message message))))))
    [[:messages.actions/add-to-chat-no-save topic-id message]]))

(defn streaming-chunk-effects
  "Builds effects for an incoming assistant message chunk.
   Appends to existing response or starts a new one."
  [state message-type chunk-text message-id]
  (let [continuing? (continuing? state message-type)
        topic-id    (topic-state/get-active-topic-id state)]
    (if continuing?
      [(append-to-response state chunk-text)]
      (start-response topic-id message-type chunk-text message-id))))

(defn- websearch? [update]
  (= "WebSearch" (get-in update [:meta :claude-code :tool-name])))

(defn- mint-websearch-tool-call [update message-id]
  {:id               message-id
   :type             :tool-call
   :tool-call-id     (:tool-call-id update)
   :tool             :web-search
   :tool-call-status (or (:status update) "pending")
   :query            nil
   :text             ""})

(defn- mint-read-tool-call [update message-id]
  {:id               message-id
   :type             :tool-call
   :tool-call-id     (:tool-call-id update)
   :tool             :read
   :tool-call-status "completed"
   :text             (acp-codec/acp-read-display-label update)})

(defn handle-tool-event
  "Routes ACP tool session updates to tool-call.actions/start, /update, or
   :topic.actions/append-pending-diffs.

   WebSearch: :tool-call mints the placeholder; :tool-call-update patches
   :tool-call-status and :query. Read: emits a one-shot :tool-call message
   minted from the :tool-call-update with file metadata."
  [_state update message-id]
  (cond
    (and (websearch? update) (= :tool-call (:session-update update)))
    [[:tool-call.actions/start (mint-websearch-tool-call update message-id)]]

    (and (websearch? update) (= :tool-call-update (:session-update update)))
    (let [new-query (get-in update [:raw-input :query])
          patch     (cond-> {}
                      (:status update) (assoc :tool-call-status (:status update))
                      new-query        (assoc :query new-query))]
      (when (seq patch)
        [[:tool-call.actions/update (:tool-call-id update) patch]]))

    (and (acp-codec/tool-response-read-event? update)
         (acp-codec/tool-response-read-with-file-metadata? update))
    [[:tool-call.actions/start (mint-read-tool-call update message-id)]]

    (acp-codec/tool-response-has-diffs? update)
    [[:topic.actions/append-pending-diffs (acp-codec/tool-response-diffs update)]]))

(defn session-update
  "Handles incoming ACP session updates (streaming chunks, errors, etc).

  update: acp-codec/AcpUpdate"
  [state {:keys [update]}]
  (let [update-type (:session-update update)]
    (cond
      ;; Streaming text chunks (assistant, reasoning)
      (#{:agent-message-chunk :agent-thought-chunk} update-type)
      (streaming-chunk-effects state
                               (get acp-codec/acp-chunk->message-type update-type)
                               (acp-codec/acp-update-text update)
                               (schema/generate-message-id))

      ;; Tool updates (call + status)
      (#{:tool-call :tool-call-update} update-type)
      (handle-tool-event state update (schema/generate-message-id)))))

(defn session-ready
  "Session created successfully. Save acp-session-id to topic."
  [_state topic-id acp-session-id]
  (js/console.log "[ACP] Session ready:" acp-session-id "for topic:" topic-id)
  [[:loading.actions/set-loading? topic-id false]
   [:effects/save (topic-state/acp-session-id-path topic-id) acp-session-id]])

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
