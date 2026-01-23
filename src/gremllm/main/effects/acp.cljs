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
  (acp-js/newSession cwd))

(defn prompt
  "Send prompt to ACP agent. Returns promise of result."
  [session-id text]
  (acp-js/prompt session-id text))

(defn shutdown
  "Terminate ACP subprocess."
  []
  (acp-js/shutdown))

(defn set-dispatcher!
  "Wire CLJS dispatch function into JS module."
  [dispatch-fn]
  (acp-js/setDispatcher dispatch-fn))
