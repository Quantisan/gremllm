(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require ["acp" :as acp-factory]))

;; TODO: consider adopting https://github.com/stuartsierra/component
(defonce ^:private state (atom nil))
(defonce ^:private initialize-in-flight (atom nil))

(defn- create-connection
  "Thin wrapper for testability via with-redefs."
  [opts]
  (acp-factory/createConnection opts))

(defn initialize
  "Initialize ACP connection eagerly. Idempotent.
   on-session-update: callback receiving raw JS session update params from SDK."
  [on-session-update]
  (cond
    @state
    (js/Promise.resolve nil)

    @initialize-in-flight
    (:promise @initialize-in-flight)

    :else
    (let [^js result (create-connection
                       #js {:onSessionUpdate on-session-update})
          conn       (.-connection result)
          subprocess (.-subprocess result)
          init-promise
          (-> (.initialize conn
                #js {:protocolVersion    (.-protocolVersion result)
                     :clientCapabilities #js {:fs #js {} :terminal false}
                     :clientInfo         #js {:name "gremllm"
                                              :title "Gremllm"
                                              :version "0.1.0"}})
              (.then (fn [_]
                       (reset! state {:connection conn
                                      :subprocess subprocess})
                       nil))
              (.catch (fn [err]
                        (when subprocess
                          (.kill subprocess "SIGTERM"))
                        (throw err)))
              (.finally (fn []
                          (reset! initialize-in-flight nil))))]
      (reset! initialize-in-flight {:promise init-promise
                                    :subprocess subprocess})
      init-promise)))

(defn- ^js conn! []
  (or (:connection @state)
      (throw (js/Error. "ACP not initialized"))))

(defn new-session
  "Create new ACP session for given working directory."
  [cwd]
  (-> (.newSession (conn!) #js {:cwd cwd :mcpServers #js []})
      (.then (fn [result] (.-sessionId result)))))

(defn resume-session
  "Resume existing ACP session by ID."
  [cwd acp-session-id]
  (-> (.unstable_resumeSession (conn!)
        #js {:sessionId acp-session-id :cwd cwd :mcpServers #js []})
      (.then (fn [_] acp-session-id))))

(defn prompt
  "Send prompt to ACP agent. Returns promise of result."
  [acp-session-id content-blocks]
  (.prompt (conn!)
    #js {:sessionId acp-session-id
         :prompt    (clj->js content-blocks)}))

(defn shutdown
  "Terminate ACP subprocess."
  []
  (let [^js initialized-subprocess (:subprocess @state)
        ^js inflight-subprocess    (:subprocess @initialize-in-flight)]
    (when initialized-subprocess
      (.kill initialized-subprocess "SIGTERM"))
    (when (and inflight-subprocess
               (not (identical? inflight-subprocess initialized-subprocess)))
      (.kill inflight-subprocess "SIGTERM"))
    (reset! initialize-in-flight nil)
    (reset! state nil)))
