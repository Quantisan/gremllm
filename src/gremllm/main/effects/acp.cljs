(ns gremllm.main.effects.acp
  "ACP effect handlers - thin wrappers around JS module.
   These perform I/O; business logic lives in actions."
  (:require ["acp" :as acp-js]))

(defn initialize
  "Initialize ACP connection. Idempotent."
  []
  (acp-js/initialize))

(defn new-session
  "Create new ACP session for given working directory."
  [cwd]
  (js/console.log "[acp] invoking new-session, cwd:" cwd)
  (acp-js/newSession cwd))

(defn resume-session
  "Resume existing ACP session by ID."
  [cwd acp-session-id]
  (js/console.log "[acp] invoking resume-session, cwd:" cwd "acp-session-id:" acp-session-id)
  (acp-js/resumeSession cwd acp-session-id))

(defn prompt
  "Send prompt to ACP agent. Returns promise of result."
  [acp-session-id text]
  (js/console.log "[acp] invoking prompt, acp-session-id:" acp-session-id)
  (acp-js/prompt acp-session-id text))

(defn shutdown
  "Terminate ACP subprocess."
  []
  (acp-js/shutdown))

(defn set-dispatcher!
  "Wire CLJS dispatch function into JS module."
  [dispatch-fn]
  (acp-js/setDispatcher dispatch-fn))
