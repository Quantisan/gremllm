(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require [clojure.string :as str]
            [gremllm.schema.codec.acp :as acp-codec]
            [gremllm.schema.codec.acp.permission :as acp-permission]
            [nexus.registry :as nxr]
            ["/js/acp/index" :as acp-factory]
            ["fs/promises" :as fsp]))

;; _meta.claudeCode.options overrides for the pinned claude-agent-acp session-setup path
;; (node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137).
;; That path merges env/settingSources/model/thinking but hardcodes executable: process.execPath,
;; so :executable cannot be overridden here.
;; ELECTRON_RUN_AS_NODE=1 makes process.execPath act as Node instead of relaunching the window;
;; depends on FuseV1Options.RunAsNode (https://packages.electronjs.org/fuses).
;; SDK refs: sdk.d.ts:56 (model aliases), sdk.d.ts:5371 (ThinkingEnabled).
(def ^:private session-meta
  #js {:claudeCode
       #js {:options
            #js {:env            #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources #js []
                 :model          "sonnet"
                 :thinking       #js {:type "enabled" :budgetTokens 20480 :display "summarized"}}}})

;; TODO: consider adopting https://github.com/stuartsierra/component
;; @state is nil, or:
;;   {:connection    <ClientSideConnection, or nil until init resolves>
;;    :dispose-agent <fn>
;;    :ready         <init-promise>}
(defonce ^:private state (atom nil))

(defn slice-content-by-lines
  "Slice string content by 1-indexed line offset and limit.
   Returns full content when both line and limit are nil."
  [content line limit]
  (if (and (nil? line) (nil? limit))
    content
    (str/join "\n"
      (cond->> (drop (max 0 (dec (or line 1)))
                     (str/split content #"\n" -1))
        limit (take limit)))))

(defn read-text-file
  "Read file from disk with optional line/limit slicing.
   Returns JS promise resolving to #js {:content \"...\"}
   matching ACP SDK's expected readTextFile return shape.

   Request params shape:
   type ReadTextFileRequest = {
     _meta?: { [key: string]: unknown } | null;
     limit?: number | null;
     line?: number | null;
     path: string;
     sessionId: string;
   }
   Note: ACP provides sessionId in params; it is intentionally unused here.

   Reference:
   https://agentclientprotocol.github.io/typescript-sdk/types/ReadTextFileRequest.html"
  [^js params]
  (let [file-path (.-path params)
        line      (.-line params)
        limit     (.-limit params)]
    (when-not (and (string? file-path) (seq file-path))
      (throw (js/Error. "readTextFile requires a non-empty path")))
    (-> (.readFile fsp file-path "utf8")
        (.then (fn [content]
                 #js {:content (slice-content-by-lines content line limit)})))))

(defn- create-connection
  "Thin wrapper for testability via with-redefs."
  [opts]
  (acp-factory/createConnection opts))

(def ^:private client-info
  #js {:name "gremllm" :title "Gremllm" :version "0.1.0"})

(def ^:private client-capabilities
  #js {:fs       #js {:readTextFile true :writeTextFile true}
       :terminal false})

(defn- start-connection!
  "Perform ACP handshake, update state, and call dispose-agent on failure.
   Returns the initialization promise."
  [^js conn ^js protocol-version dispose-agent]
  (-> (.initialize conn
        #js {:protocolVersion    protocol-version
             :clientCapabilities client-capabilities
             :clientInfo         client-info})
      (.then (fn [_]
               (when @state
                 (swap! state assoc :connection conn))
               nil))
      (.catch (fn [err]
                (if @state
                  (-> (dispose-agent)
                      (.then (fn [_]
                               (reset! state nil)
                               (throw err))))
                  (throw err))))))

(defn- make-write-callback
  "Build a fire-and-forget write tap. Coerces raw JS params and
   calls on-write with a map of :path, :session-id, :content-length."
  [on-write]
  (fn [params]
    (try
      (on-write {:path           (.-path params)
                 :session-id     (.-sessionId params)
                 :content-length (when (string? (.-content params))
                                   (.-length (.-content params)))})
      (catch :default e
        (js/console.error "ACP write request coercion failed" e params)))))

(defn initialize
  "Initialize ACP connection eagerly. Idempotent.
   opts keys:
     :on-session-update  callback receiving raw JS session update params from SDK.
     :on-permission      optional tap receiving coerced+enriched permission request params.
     :on-write           optional tap receiving coerced writeTextFile params."
  [{:keys [on-session-update on-permission on-write]}]
  (if-let [ready (:ready @state)]
    ready
    (let [;; Per-connection tool-name tracker. Seeded from session updates so
          ;; the permission resolver can enrich requests that arrive without a
          ;; toolName (ACP sends it in _meta.claudeCode.toolName on the session
          ;; update, not always in the permission request directly).
          tool-names   (atom {})
          session-cb   (fn [raw-params]
                         (try
                           (swap! tool-names acp-permission/remember-tool-name
                                  (acp-codec/acp-session-update-from-js raw-params))
                           (catch :default _))
                         (when on-session-update (on-session-update raw-params)))
          resolve-cb   (fn [^js raw-params session-cwd]
                         (try
                           (let [enriched (->> raw-params
                                               acp-codec/acp-permission-request-from-js
                                               (acp-permission/enrich-permission-params @tool-names))
                                 outcome  (acp-permission/resolve-permission enriched session-cwd)]
                             (when on-permission
                               (try (on-permission enriched)
                                    (catch :default e
                                      (js/console.error "ACP on-permission tap failed (non-fatal; resolved outcome is unaffected)" e))))
                             (acp-codec/acp-permission-outcome-to-js outcome))
                           (catch :default e
                             (js/console.error "ACP permission resolve failed" e "raw params:" raw-params)
                             #js {:outcome #js {:outcome "cancelled"}})))
          ^js result   (create-connection
                         #js {:onSessionUpdate   session-cb
                              :onReadTextFile    read-text-file
                              :onWriteTextFile   (when on-write (make-write-callback on-write))
                              :resolvePermission resolve-cb})
          dispose-agent (.-disposeAgent result)
          ready-promise (start-connection! (.-connection result)
                                           (.-protocolVersion result)
                                           dispose-agent)]
      (reset! state {:connection nil :dispose-agent dispose-agent :ready ready-promise})
      ready-promise)))

(defn- ^js conn! []
  (or (:connection @state)
      (throw (js/Error. "ACP not initialized"))))

(defn new-session
  "Create new ACP session for given working directory."
  [cwd]
  (-> (.newSession (conn!) #js {:cwd cwd :mcpServers #js [] :_meta session-meta})
      (.then (fn [result] (.-sessionId result)))))

(defn resume-session
  "Resume existing ACP session by ID."
  [cwd acp-session-id]
  (-> (.resumeSession (conn!)
        #js {:sessionId acp-session-id :cwd cwd :mcpServers #js [] :_meta session-meta})
      (.then (fn [_] acp-session-id))))

(defn prompt
  "Send prompt to ACP agent. Returns promise of result."
  [acp-session-id content-blocks]
  (.prompt (conn!)
    #js {:sessionId acp-session-id
         :prompt    (clj->js content-blocks)}))

(defn make-session-update-callback
  "Build the on-session-update callback for ACP initialize.
   Coerces raw JS params, dispatches to store. When on-update is
   provided, calls it with the coerced value before dispatch."
  [store on-update]
  (fn [params]
    (try
      (let [coerced (acp-codec/acp-session-update-from-js params)]
        (when on-update (on-update coerced))
        (nxr/dispatch store {} [[:acp.events/session-update coerced]]))
      (catch :default e
        (js/console.error "ACP session update coercion failed" e "raw params:" params)))))

(defn shutdown
  "Tear down in-process ACP agent. Returns a promise that resolves after dispose
   settles so callers (e.g. a before-quit hook) can await cleanup."
  []
  (if-let [{:keys [dispose-agent]} @state]
    (do (reset! state nil)
        (dispose-agent))
    (js/Promise.resolve nil)))
