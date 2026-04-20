(ns gremllm.main.effects.acp-trace
  (:require ["fs/promises" :as fsp]
            ["path" :as path]
            [clojure.string :as str]))

(defn- safe-version [pkg-path]
  (try (.-version (js/require pkg-path))
       (catch :default _ nil)))

(defn versions
  "Read installed package version strings. Returns nil for any package
   whose package.json cannot be located."
  []
  {:claude-agent-acp (safe-version "@agentclientprotocol/claude-agent-acp/package.json")
   :acp-sdk          (safe-version "@agentclientprotocol/sdk/package.json")
   :gremllm          (safe-version "../../../package.json")})

(defn make-recorder
  "Create a new event recorder.
   Returns {:events atom :on-session-update fn :on-permission fn :on-write fn :on-read fn}.

   Wire the taps into acp/initialize:
     (acp/make-session-update-callback store (:on-session-update r))  -> on-session-update arg
     (:on-permission r)                                                -> on-permission arg
     (:on-write r)                                                     -> on-write arg
     (:on-read r)                                                      -> on-read arg"
  []
  (let [start  (js/performance.now)
        events (atom [])]
    {:events             events
     :on-session-update
     (fn [coerced]
       (swap! events conj {:ts (- (js/performance.now) start) :kind :session-update :payload coerced}))
     :on-permission
     (fn [coerced]
       (swap! events conj {:ts (- (js/performance.now) start) :kind :permission :payload coerced}))
     :on-write
     (fn [params]
       (swap! events conj {:ts (- (js/performance.now) start) :kind :write :payload params}))
     :on-read
     (fn [params]
       (swap! events conj {:ts (- (js/performance.now) start) :kind :read :payload params}))}))

(defn write-trace!
  "Serialize recorded events to target-dir/scenario-<ISO>.edn.
   metadata map keys: :versions, :prompt, :doc-path, :result.
   Returns a Promise resolving to the full file path written."
  [target-dir scenario-name metadata events-atom]
  (let [now      (.toISOString (js/Date.))
        filename (str scenario-name "-"
                      (str/replace now #"[:.Z]" "-")
                      ".edn")
        dest     (.join path target-dir filename)
        data     (merge {:scenario   scenario-name
                         :started-at now
                         :events     @events-atom}
                        metadata)]
    (-> (.mkdir fsp target-dir #js {:recursive true})
        (.then (fn [_] (.writeFile fsp dest (pr-str data) "utf8")))
        (.then (fn [_] dest)))))
