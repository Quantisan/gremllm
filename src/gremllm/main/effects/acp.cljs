(ns gremllm.main.effects.acp
  "ACP effect handlers - owns connection lifecycle.
   JS module is a thin factory; CLJS manages state and public API."
  (:require [clojure.string :as str]
            [gremllm.schema.codec :as codec]
            [nexus.registry :as nxr]
            ["/js/acp/index" :as acp-factory]
            ["fs/promises" :as fsp]))

;; TODO: consider adopting https://github.com/stuartsierra/component
(defonce ^:private state (atom nil))
(defonce ^:private initialize-in-flight (atom nil))
;; Tracks an in-flight shutdown dispose. Atoms (state, initialize-in-flight) are reset
;; synchronously in shutdown; this atom lets initialize wait for the async dispose to
;; settle before creating a new connection (init-during-shutdown race).
(defonce ^:private shutdown-promise (atom nil))

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
  "Perform ACP handshake, update state atoms, and call dispose-agent on failure.
   Returns the initialization promise."
  [^js conn ^js protocol-version dispose-agent]
  (-> (.initialize conn
        #js {:protocolVersion    protocol-version
             :clientCapabilities client-capabilities
             :clientInfo         client-info})
      (.then (fn [_]
               (reset! state {:connection conn :dispose-agent dispose-agent})
               nil))
      (.catch (fn [err]
                (-> (dispose-agent)
                    (.then (fn [_] (throw err))))))
      (.finally (fn []
                  (reset! initialize-in-flight nil)))))

(defn- make-permission-callback
  "Build a fire-and-forget permission tap. Coerces raw JS params and
   calls on-permission with the coerced value."
  [on-permission]
  (fn [params]
    (js/console.log "ACP requestPermission tap fired" params)
    (try
      (on-permission (codec/acp-permission-request-from-js params))
      (catch :default e
        (js/console.error "ACP permission request coercion failed" e params)))))

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
     :on-permission      optional tap receiving coerced permission request params.
     :on-write           optional tap receiving coerced writeTextFile params."
  [{:keys [on-session-update on-permission on-write] :as opts}]
  (cond
    @state
    (js/Promise.resolve nil)

    @initialize-in-flight
    (:promise @initialize-in-flight)

    @shutdown-promise
    (.then @shutdown-promise (fn [_] (initialize opts)))

    :else
    (let [^js result       (create-connection
                             #js {:onSessionUpdate     on-session-update
                                  :onReadTextFile      read-text-file
                                  :onRequestPermission (when on-permission
                                                         (make-permission-callback on-permission))
                                  :onWriteTextFile     (when on-write
                                                         (make-write-callback on-write))})
          dispose-agent    (.-disposeAgent result)
          init-promise     (start-connection! (.-connection result)
                                              (.-protocolVersion result)
                                              dispose-agent)]
      (reset! initialize-in-flight {:promise       init-promise
                                    :dispose-agent dispose-agent})
      init-promise)))

(defn- ^js conn! []
  (or (:connection @state)
      (throw (js/Error. "ACP not initialized"))))

;; TODO: session-meta embeds adapter-internal knobs whose shape is keyed to acp-agent.js:1095.
;; This effect file currently owns three concerns: connection lifecycle, ACP public API, and
;; Claude-adapter overrides. The overrides want their own home before non-spike use.
(def ^:private session-meta
  "Adapter spreads params._meta.claudeCode.options into the child-spawn config.
   - ELECTRON_RUN_AS_NODE=1 is required so the Electron binary (process.execPath
     in packaged mode) behaves as a Node interpreter instead of booting a new app
     window. See acp-agent.js:1115 for the env merge.
   - settingSources: [] disables the Claude Code SDK's loading of user/project/local
     settings files, reducing filesystem-watcher pressure. The adapter's own
     SettingsManager is separate and already disposed on session teardown.
     See acp-agent.js:1112-1114 for the override path."
  #js {:claudeCode
       #js {:options
            #js {:env            #js {:ELECTRON_RUN_AS_NODE "1"}
                 :settingSources #js []}}})

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
  "Tear down in-process ACP agent. Returns a promise that resolves after all
   disposes settle so callers (e.g. a before-quit hook) can await cleanup."
  []
  (let [initialized-dispose (:dispose-agent @state)
        inflight-dispose    (:dispose-agent @initialize-in-flight)
        promises            (cond-> []
                              initialized-dispose
                              (conj (initialized-dispose))
                              (and inflight-dispose
                                   (not (identical? inflight-dispose initialized-dispose)))
                              (conj (inflight-dispose)))]
    (reset! initialize-in-flight nil)
    (reset! state nil)
    (if (seq promises)
      (let [p (-> (js/Promise.all (clj->js promises))
                  (.then (fn [_] (reset! shutdown-promise nil))))]
        (reset! shutdown-promise p)
        p)
      (do (reset! shutdown-promise nil)
          (js/Promise.resolve nil)))))
