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

(defn handle-tool-event
  "Handles ACP tool-related session updates.
   Returns chat effects for displayable tool calls and reads,
   or diff effects for tool-call-updates with diffs."
  ;; TODO: this cond mixes per-tool predicates (websearch?) with generic
  ;; tool-response branches. When a second displayable tool lands, replace
  ;; the per-tool branches with a dispatch keyed on tool-name — leave that
  ;; decision until the second instance exists.
  ;;
  ;; WebSearch: :tool-call mints the placeholder (wire :status defaults to "pending", no query);
  ;; :tool-call-update patches :query and :tool-call-status on the Message.
  [state update message-id]
  (cond
    (and (websearch? update) (= :tool-call (:session-update update)))
    (let [topic-id (topic-state/get-active-topic-id state)
          msg      {:id           message-id
                    :type         :tool-search
                    :tool-call-id (:tool-call-id update)
                    :tool-call-status (or (:status update) "pending")
                    :query        nil
                    :text         ""}]
      (when-not (m/validate schema/Message msg)
        (throw (js/Error. (str "Invalid :tool-search Message: "
                               (pr-str (m/explain schema/Message msg))))))
      [[:messages.actions/add-to-chat-no-save topic-id msg]])

    (and (websearch? update) (= :tool-call-update (:session-update update)))
    (let [new-query (get-in update [:raw-input :query])
          patch     (cond-> {}
                      (:status update) (assoc :tool-call-status (:status update))
                      new-query        (assoc :query new-query))]
      (when (seq patch)
        [[:topic.actions/patch-message-by-tool-call-id (:tool-call-id update) patch]]))

    (and (acp-codec/tool-response-read-event? update)
         (acp-codec/tool-response-read-with-file-metadata? update))
    (start-response (topic-state/get-active-topic-id state)
                    :tool-use
                    (acp-codec/acp-read-display-label update)
                    message-id)

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
