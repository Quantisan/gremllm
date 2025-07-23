(ns gremllm.main.actions
  (:require [nexus.registry :as nxr]
            [gremllm.main.actions.topic :as topic-actions]
            [gremllm.main.actions.ipc :as ipc-actions]
            [gremllm.main.actions.secrets :as secrets-actions]
            [gremllm.main.effects.llm :as llm-effects]
            [gremllm.main.effects.topic :as topic-effects]
            [gremllm.main.utils :refer [nxr-result]]
            ["electron/main" :refer [BrowserWindow]]))

;; Register how to extract state from the system
(nxr/register-system->state! deref)

;; Placeholder for environment variables
(nxr/register-placeholder! :env/anthropic-api-key
  (fn [store]
    (or (.-ANTHROPIC_API_KEY (.-env js/process))
        (some-> (nxr/dispatch store {} [[:secrets.effects/load :anthropic-api-key]])
                nxr-result
                :value))))

;; Electron platform helpers (used by effects)
(defn send-to-focused-window [channel]
  (some-> (.getFocusedWindow BrowserWindow)
          .-webContents
          (.send channel)))

;; Effects
(nxr/register-effect! :effects/trigger-save-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "topic/save")))

(nxr/register-effect! :effects/trigger-open-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "topic/open")))

(nxr/register-effect! :effects/trigger-settings-in-renderer
  (fn [_ _ _]
    (send-to-focused-window "menu:settings")))

(nxr/register-effect! :ipc.effects/reply ipc-actions/reply)
(nxr/register-effect! :ipc.effects/reply-error ipc-actions/reply-error)

;; Generic promise handling
(nxr/register-effect! :ipc.effects/promise->reply ipc-actions/promise->reply)

;; LLM effects
(nxr/register-effect! :chat.effects/send-message
  (fn [{:keys [dispatch]} _ messages api-key]
    (dispatch [[:ipc.effects/promise->reply (llm-effects/query-llm-provider messages api-key)]])))

(nxr/register-effect! :ipc.effects/save-topic
  (fn [_ _ topic-clj topics-dir]
    (let [save-plan (topic-actions/prepare-save nil nil topic-clj topics-dir)]
      (topic-effects/save save-plan))))

(nxr/register-effect! :ipc.effects/load-topic
  (fn [_ _ topics-dir]
    (topic-effects/load topics-dir topic-actions/topic-file-pattern)))

(nxr/register-effect! :secrets.effects/save secrets-actions/save)
(nxr/register-effect! :secrets.effects/load secrets-actions/load)
(nxr/register-effect! :secrets.effects/delete secrets-actions/del)
(nxr/register-effect! :secrets.effects/list-keys secrets-actions/list-keys)
(nxr/register-effect! :secrets.effects/load-all secrets-actions/load-all)
