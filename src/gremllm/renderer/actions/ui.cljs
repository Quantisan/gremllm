(ns gremllm.renderer.actions.ui
  (:require [gremllm.schema :as schema]
            [gremllm.renderer.state.form :as form-state]
            [gremllm.renderer.state.ui :as ui-state]))

;; UI Actions
;; This file contains actions for two related namespaces:
;; - :ui.actions/* - Global UI concerns (modals, scrolling, focus)
;; - :form.actions/* - Chat input form (typing, submit, file attachments)

(defn update-input [_state value]
  [[:effects/save form-state/user-input-path value]])

(defn clear-input [_state]
  [[:effects/save form-state/user-input-path ""]])

(defn handle-submit-keys [_state {:keys [key shift?]}]
  (when (and (= key "Enter") (not shift?))
    [[:effects/prevent-default]
     [:form.actions/submit]]))

(defn submit-messages [state]
  (let [text (form-state/get-user-input state)]
    (when-not (empty? text)
      (let [new-user-message {:id   (schema/generate-message-id)
                              :type :user
                              :text text}]
        [[:messages.actions/add-to-chat new-user-message]
         [:form.actions/clear-input]
         [:ui.actions/focus-chat-input]
         [:ui.actions/scroll-chat-to-bottom]
         [:acp.actions/send-prompt text]]))))

;; Pure action for scrolling chat to bottom
(defn scroll-chat-to-bottom [_state]
  (let [element-id "chat-messages-container"]
    [[:effects/set-element-property
      {:on-element   [:dom/element-by-id element-id]
       :set-property "scrollTop"
       :to-value     [:dom.element/property [:dom/element-by-id element-id] "scrollHeight"]}]]))

;; Pure action for focusing chat input
(defn focus-chat-input [_state]
  [[:effects/focus ".chat-input"]])

;; Pure action for handling dragover event
(defn handle-dragover [_state]
  [[:effects/prevent-default]])

;; Pure action for handling file drop
(defn handle-file-drop
  "Saves dropped files to pending attachments state.
  Files arrive from :event/dropped-files placeholder as DOM File metadata.
  Shape saved: vector of {:name :size :type :path}."
  [_state files]
  (if (seq files)
    [[:effects/save form-state/pending-attachments-path (vec files)]]
    []))

(defn clear-pending-attachments [_state]
  [[:effects/save form-state/pending-attachments-path []]])

(defn show-settings [_state]
  ;; Refresh system info to ensure settings modal displays current API key status
  [[:system.actions/request-info]
   [:effects/save ui-state/showing-settings-path true]])

(defn hide-settings [_state]
  ;; Refresh system info to ensure has-any-api-key? is up-to-date
  [[:system.actions/request-info]
   [:effects/save ui-state/showing-settings-path false]])

(defn toggle-nav [state]
  (let [expanded? (ui-state/nav-expanded? state)]
    [[:effects/save ui-state/nav-expanded-path (not expanded?)]]))
