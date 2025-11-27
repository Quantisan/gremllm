(ns gremllm.main.actions.chat
  "Pure action functions for chat domain operations in the main process."
  (:require [gremllm.main.state :as state]
            [gremllm.schema :as schema]))

(defn send-message-from-ipc
  [state messages model api-key attachment-paths]
  (let [workspace-dir    (state/get-workspace-dir state)
        has-attachments? (and attachment-paths (seq attachment-paths) workspace-dir)]
    ;; CHECKPOINT 3: Routing decision
    (js/console.log "[CHECKPOINT 3] Main: Routing decision"
                    (clj->js {:has-attachments? has-attachments?
                              :file-paths-count (count (or attachment-paths []))
                              :workspace-dir workspace-dir
                              :taking-path (if has-attachments? "with-attachments" "normal")}))
    (if has-attachments?
      ;; Has attachments - dispatch orchestrating action
      [[:chat.actions/send-message-with-attachments workspace-dir attachment-paths messages model api-key]]
      ;; No attachments - normal flow
      [[:chat.effects/send-message messages model api-key]])))

(defn send-message-with-attachments
  "Orchestrates attachment processing flow: process files → load content → enrich → send.
  Pure action that returns effect description to start the flow."
  [state workspace-dir file-paths messages model api-key]
  [[:attachment.effects/process-batch-then-continue workspace-dir file-paths messages model api-key]])

(defn send-message-with-loaded-attachments
  "Orchestrates loading attachment content and sending.
  Receives AttachmentRefs from previous step, returns effect to load content."
  [state workspace-dir attachment-refs messages model api-key]
  [[:attachment.effects/load-then-enrich workspace-dir attachment-refs messages model api-key]])

(defn enrich-and-send
  "Pure: transforms loaded attachment data to API format, enriches messages, returns send effect.
  Receives vector of [AttachmentRef Buffer] pairs from load effect."
  [state loaded-pairs messages model api-key]
  (let [;; Pure: transform each ref+content pair to API format
        api-attachments (mapv (fn [[ref content]]
                                (schema/attachment-ref->api-format ref content))
                              loaded-pairs)
        ;; Pure: enrich first message with API-ready attachments
        enriched-messages (update messages 0 assoc :attachments api-attachments)]
    ;; CHECKPOINT 6: Message enrichment
    (js/console.log "[CHECKPOINT 6] Main: Messages enriched"
                    (clj->js {:api-attachments-count (count api-attachments)
                              :api-attachments-info (mapv (fn [att]
                                                            {:mime-type (:mime-type att)
                                                             :data-length (count (:data att))
                                                             :data-preview (subs (:data att) 0 (min 50 (count (:data att))))})
                                                          api-attachments)
                              :enriched-first-message-has-attachments? (some? (:attachments (first enriched-messages)))
                              :enriched-messages-count (count enriched-messages)}))
    ;; Return effect to send enriched messages
    [[:chat.effects/send-message enriched-messages model api-key]]))
