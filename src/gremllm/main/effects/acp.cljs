(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require [clojure.string :as str]
            [gremllm.schema.codec :as codec]
            [gremllm.schema.codec.acp-permission :as acp-permission]
            [nexus.registry :as nxr]
            ["/js/acp/index" :as acp-factory]
            ["fs/promises" :as fsp]))

;; Claude-adapter overrides for ACP session setup.
;;
;; These knobs are keyed to the pinned claude-agent-acp session setup path
;; (local package node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137).
;; Adapter reads params._meta.claudeCode.options, merges env, and defaults settingSources
;; in that path. It then hardcodes executable: process.execPath for the non-static-binary
;; branch, so _meta.claudeCode.options.executable is not a working override in this repo's
;; pinned adapter version.
;;
;; Gremllm therefore injects:
;; - ELECTRON_RUN_AS_NODE=1 so the packaged Electron binary (process.execPath) acts as a
;;   Node interpreter instead of relaunching the app window. This depends on
;;   FuseV1Options.RunAsNode remaining enabled; see https://packages.electronjs.org/fuses
;; - settingSources: [] to suppress Claude Code SDK user/project/local settings loading for
;;   Gremllm sessions. The adapter's own SettingsManager lifecycle remains separate.
(def ^:private session-meta
  #js {:claudeCode
       #js {:options
            #js {:env            #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources #js []}}})

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
                                  (codec/acp-session-update-from-js raw-params))
                           (catch :default _))
                         (when on-session-update (on-session-update raw-params)))
          resolve-cb   (fn [^js raw-params session-cwd]
                         (try
                           (let [enriched (->> raw-params
                                               codec/acp-permission-request-from-js
                                               (acp-permission/enrich-permission-params @tool-names))
                                 outcome  (acp-permission/resolve-permission enriched session-cwd)]
                             (when on-permission
                               (try (on-permission enriched)
                                    (catch :default e
                                      (js/console.error "ACP on-permission tap failed (non-fatal; resolved outcome is unaffected)" e))))
                             (codec/acp-permission-outcome-to-js outcome))
                           (catch :default e
                             (js/console.error "ACP permission resolve failed" e raw-params)
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
  (-> (.unstable_resumeSession (conn!)
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
      (let [coerced (codec/acp-session-update-from-js params)]
        (when on-update (on-update coerced))
        (nxr/dispatch store {} [[:acp.events/session-update coerced]]))
      (catch :default e
        (js/console.error "ACP session update coercion failed" e params)))))

(defn shutdown
  "Tear down in-process ACP agent. Returns a promise that resolves after dispose
   settles so callers (e.g. a before-quit hook) can await cleanup."
  []
  (if-let [{:keys [dispose-agent]} @state]
    (do (reset! state nil)
        (dispose-agent))
    (js/Promise.resolve nil)))
