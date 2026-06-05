(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require [clojure.string :as str]
            [gremllm.main.effects.acp.permission :as permission]
            [gremllm.main.electron :as electron]
            [gremllm.schema.codec.acp :as acp-codec]
            [nexus.registry :as nxr]
            ["/js/acp/index" :as acp-factory]
            ["fs/promises" :as fsp]
            ["path" :as path]))

;; _meta.claudeCode.options overrides for the pinned claude-agent-acp session-setup path
;; (node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137).
;; That path merges env/settingSources/model/thinking but hardcodes executable: process.execPath,
;; so :executable cannot be overridden here.
;; ELECTRON_RUN_AS_NODE=1 makes process.execPath act as Node instead of relaunching the window;
;; depends on FuseV1Options.RunAsNode (https://packages.electronjs.org/fuses).
;;
;; :disallowedTools still blocks MultiEdit/NotebookEdit because the SDK does not
;; populate diff content in their session/request_permission payload (verified in
;; node_modules/@agentclientprotocol/claude-agent-acp/dist/tools.js — neither case
;; exists, so they fall through to the default branch which emits no :diff blocks).
;; Edit/Write are intentionally allowed: they ARE handled by toolInfoFromToolUse,
;; so the deferred-permission flow can route their proposed diff to the user.
;; SDK refs: sdk.d.ts:56 (model aliases), sdk.d.ts:5371 (ThinkingEnabled).
(def ^:private session-meta
  #js {:claudeCode
       #js {:options
            #js {:env             #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources  #js []
                 :model           "sonnet"
                 :thinking        #js {:type "enabled" :budgetTokens 20480 :display "summarized"}
                 :disallowedTools #js ["MultiEdit" "NotebookEdit"]}}})

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
  #js {:fs       #js {:readTextFile true :writeTextFile false}
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


(defn- set-claude-executable-for-packaged-app!
  "In a packaged app the ACP SDK's native binary lives in app.asar.unpacked,
   but require.resolve returns an asar-virtual path that spawn can't execute.
   Setting this env var makes the SDK skip its own resolution entirely."
  []
  (when-let [app (electron/get-app)]
    (when (.-isPackaged app)
      (let [platform-specific-package (str "claude-agent-sdk-"
                                         (.-platform js/process) "-"
                                         (.-arch js/process))
            unpacked-binary-path    (path/join (.-resourcesPath js/process)
                                               "app.asar.unpacked" "node_modules" "@anthropic-ai"
                                               platform-specific-package "claude")]
        (set! (.. js/process -env -CLAUDE_CODE_EXECUTABLE) unpacked-binary-path)))))

(defn initialize
  "Initialize ACP connection eagerly. Idempotent.
   opts keys:
     :on-session-update          callback receiving raw JS session update params from SDK.
     :on-permission-request      optional tap receiving enriched permission request params
                                 for every SDK resolvePermission call (immediate or deferred).
     :on-awaiting-user-decision  optional tap receiving enriched permission request params
                                 only when the resolver defers to user input (in-workspace edit).
                                 Fires *after* the resolver is registered, so a synchronous call
                                 to record-decision! from inside this callback (e.g. from tests)
                                 resolves the SDK Promise correctly."
  [{:keys [on-session-update on-permission-request on-awaiting-user-decision]}]
  (set-claude-executable-for-packaged-app!)
  (if-let [ready (:ready @state)]
    ready
    (let [session-cb  (fn [raw-params]
                        (try
                          (permission/track-tool-name!
                            (acp-codec/acp-session-update-from-js raw-params))
                          (catch :default _))
                        (when on-session-update (on-session-update raw-params)))
          resolve-cb  (permission/make-resolve-permission
                        {:on-permission-request     on-permission-request
                         :on-awaiting-user-decision on-awaiting-user-decision})
          ^js result  (create-connection
                        #js {:onSessionUpdate   session-cb
                             :onReadTextFile    read-text-file
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

;; Our accept/reject diff gate only works when edits route through
;; resolvePermission, which the agent only does in "default" permission mode.
;; Since claude-agent-acp 0.40+, a session's starting mode comes from the user's
;; global ~/.claude/settings.json, so a user with acceptEdits/bypassPermissions
;; set there would silently skip review. That mode can't be overridden at session
;; creation (it is assigned after _meta.claudeCode.options is spread in,
;; acp-agent.js:1565), so we force it afterward with setSessionMode.
(defn- pin-default-mode!
  "Set the session to 'default' permission mode. Returns a promise of acp-session-id."
  [acp-session-id]
  (-> (.setSessionMode (conn!) #js {:sessionId acp-session-id :modeId "default"})
      (.then (fn [_] acp-session-id))))

(defn new-session
  "Create new ACP session for given working directory."
  [cwd]
  (-> (.newSession (conn!) #js {:cwd cwd :mcpServers #js [] :_meta session-meta})
      (.then (fn [^js result] (.-sessionId result)))
      (.then pin-default-mode!)))

(defn resume-session
  "Resume existing ACP session by ID."
  [cwd acp-session-id]
  (-> (.resumeSession (conn!)
        #js {:sessionId acp-session-id :cwd cwd :mcpServers #js [] :_meta session-meta})
      (.then (constantly acp-session-id))
      (.then pin-default-mode!)))

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
  (permission/clear!)
  (if-let [{:keys [dispose-agent]} @state]
    (do (reset! state nil)
        (dispose-agent))
    (js/Promise.resolve nil)))
