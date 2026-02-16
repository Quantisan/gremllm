(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require ["acp" :as acp-factory]))

(defonce ^:private state (atom nil))

(defn initialize
  "Initialize ACP connection eagerly. Idempotent.
   on-session-update: callback receiving raw JS session update params from SDK."
  [on-session-update]
  (if @state
    (js/Promise.resolve nil)
    (let [result (acp-factory/createConnection
                   #js {:onSessionUpdate on-session-update})
          conn   (.-connection result)]
      (reset! state {:connection conn
                     :subprocess (.-subprocess result)})
      (.initialize conn
        #js {:protocolVersion   (.-protocolVersion result)
             :clientCapabilities #js {:fs #js {} :terminal false}
             :clientInfo         #js {:name "gremllm"
                                      :title "Gremllm"
                                      :version "0.1.0"}}))))

(defn- conn! []
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
  (when-let [{:keys [subprocess]} @state]
    (.kill subprocess "SIGTERM")
    (reset! state nil)))
