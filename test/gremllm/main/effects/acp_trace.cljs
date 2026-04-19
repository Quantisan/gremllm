(ns gremllm.main.effects.acp-trace
  (:require ["fs/promises" :as fsp]
            ["path" :as path]
            [clojure.string :as str]))

(defn versions
  "Read installed package version strings."
  []
  (let [sdk-pkg   (js/require "@agentclientprotocol/sdk/package.json")
        agent-pkg (js/require "@agentclientprotocol/claude-agent-acp/package.json")]
    {:claude-agent-acp (.-version agent-pkg)
     :acp-sdk          (.-version sdk-pkg)}))

(defn make-recorder
  "Create a new event recorder.
   Returns {:events atom :on-update fn :on-permission fn :on-write fn :on-read fn}.

   Wire the taps into acp/initialize:
     (acp/make-session-update-callback store (:on-update r))  -> on-session-update arg
     (:on-permission r)                                        -> on-permission arg
     (:on-write r)                                             -> on-write arg
     (:on-read r)                                              -> on-read arg"
  []
  (let [start  (js/Date.now)
        events (atom [])]
    {:events        events
     :on-update
     (fn [coerced]
       (swap! events conj {:ts (- (js/Date.now) start) :kind :session-update :payload coerced}))
     :on-permission
     (fn [coerced]
       (swap! events conj {:ts (- (js/Date.now) start) :kind :permission :payload coerced}))
     :on-write
     (fn [params]
       (swap! events conj {:ts (- (js/Date.now) start) :kind :write :payload params}))
     :on-read
     (fn [params]
       (swap! events conj {:ts (- (js/Date.now) start) :kind :read :payload params}))}))

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
