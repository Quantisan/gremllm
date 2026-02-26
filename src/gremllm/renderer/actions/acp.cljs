(ns gremllm.renderer.actions.acp
  "Actions for managing ACP (Agent Client Protocol) sessions."
  (:require [malli.core :as m]
            [gremllm.schema :as schema]
            [gremllm.schema.codec :as codec]
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
  [message-type chunk-text message-id]
  (let [message {:id message-id :type message-type :text chunk-text}]
    (when-not (m/validate schema/Message message)
      (throw (js/Error. (str "Invalid Message: " (pr-str (m/explain schema/Message message))))))
    [[:messages.actions/add-to-chat-no-save message]]))

(defn streaming-chunk-effects
  "Builds effects for an incoming assistant message chunk.
   Appends to existing response or starts a new one."
  [state message-type chunk-text message-id]
  (let [continuing? (continuing? state message-type)]
    (if continuing?
      [(append-to-response state chunk-text)]
      (start-response message-type chunk-text message-id))))

(defn handle-tool-event
  "Handles ACP tool-related session updates.
   Returns chat effects for displayable tool calls and reads,
   or diff effects for tool-call-updates with diffs."
  [_state update message-id]
  (cond
    (codec/read-tool-response? update)
    (start-response :tool-use (codec/acp-read-display-label update) message-id)

    (codec/has-diffs? update)
    [[:document.actions/append-pending-diffs (codec/acp-pending-diffs update)]]))

(defn session-update
  "Handles incoming ACP session updates (streaming chunks, errors, etc).

  update: codec/AcpUpdate"
  [state {:keys [update]}]
  (let [update-type (:session-update update)]
    (cond
      ;; Streaming text chunks (assistant, reasoning)
      (#{:agent-message-chunk :agent-thought-chunk} update-type)
      (streaming-chunk-effects state
                               (get codec/acp-chunk->message-type update-type)
                               (codec/acp-update-text update)
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

(defn send-prompt [state text]
  (let [topic-id       (topic-state/get-active-topic-id state)
        acp-session-id (topic-state/get-acp-session-id state topic-id)]
    (if acp-session-id
      [[:loading.actions/set-loading? topic-id true]
       [:effects/promise
        {:promise    (.acpPrompt js/window.electronAPI acp-session-id text)
         :on-success [[:loading.actions/set-loading? topic-id false]
                      [:topic.effects/auto-save topic-id]]
         :on-error   [[:loading.actions/set-loading? topic-id false]]}]]
      (js/console.error "[ACP] No session for prompt"))))
