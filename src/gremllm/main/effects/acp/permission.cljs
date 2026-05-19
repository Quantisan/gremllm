(ns gremllm.main.effects.acp.permission
  "Owns the SDK's resolvePermission seam: tool-name tracking, the registry of
   resolvers awaiting user input, and the callback the SDK invokes for each
   permission request."
  (:require [gremllm.schema.codec.acp :as acp-codec]
            [gremllm.schema.codec.acp.permission :as acp-permission]))

(defonce ^:private tool-name-by-id (atom {}))

(defonce ^:private awaiting-user-decision (atom {}))

(defn track-tool-name!
  "Seed the tool-name index from a coerced session update.
   Called by the ACP shell's onSessionUpdate handler."
  [coerced-session-update]
  (swap! tool-name-by-id acp-permission/remember-tool-name coerced-session-update))

(defn record-decision!
  "Fire the registered resolver for tool-call-id with the user's option-id.
   No-op when no resolver is registered. Called from the IPC handler when
   the renderer reports the user's accept/reject."
  [tool-call-id option-id]
  (when-let [resolver (get @awaiting-user-decision tool-call-id)]
    (swap! awaiting-user-decision dissoc tool-call-id)
    (resolver option-id)
    nil))

(defn awaiting-snapshot
  "Snapshot of resolvers currently awaiting the user. For tests/inspection."
  []
  @awaiting-user-decision)

(defn clear!
  "Reset all permission state. Called from ACP shutdown."
  []
  (reset! tool-name-by-id {})
  (reset! awaiting-user-decision {}))

(defn- register-resolver! [tool-call-id resolver]
  (when (contains? @awaiting-user-decision tool-call-id)
    (js/console.warn "[ACP] replacing pending permission resolver for" tool-call-id))
  (swap! awaiting-user-decision assoc tool-call-id resolver))

(defn- await-user-decision
  "Register a resolver under tool-call-id and return a Promise that resolves
   to a JS permission outcome once record-decision! is called for that id."
  [tool-call-id]
  (js/Promise.
    (fn [resolve _reject]
      (register-resolver! tool-call-id
                          (fn [option-id]
                            (resolve (acp-codec/acp-permission-outcome-to-js
                                       {:outcome {:outcome "selected" :option-id option-id}})))))))

(defn- fire-tap! [f arg label]
  (when f
    (try (f arg)
         (catch :default e
           (js/console.error "ACP" label "tap failed" e)))))

(defn make-resolve-permission
  "Build the SDK-shaped resolvePermission callback. Taps are optional observers:
     :on-permission-request      fired for every request (immediate or deferred)
     :on-awaiting-user-decision  fired only when the resolver defers to the user"
  [{:keys [on-permission-request on-awaiting-user-decision]}]
  (fn [^js raw-params session-cwd]
    (try
      (let [enriched (->> raw-params
                          acp-codec/acp-permission-request-from-js
                          (acp-permission/enrich-permission-params @tool-name-by-id))
            decision (acp-permission/resolve-permission enriched session-cwd)]
        (fire-tap! on-permission-request enriched "on-permission-request")
        (case (:resolution decision)
          :immediate
          (acp-codec/acp-permission-outcome-to-js {:outcome (:outcome decision)})

          :deferred
          (let [p (await-user-decision (:tool-call-id decision))]
            (fire-tap! on-awaiting-user-decision enriched "on-awaiting-user-decision")
            p)))
      (catch :default e
        (js/console.error "ACP permission resolve failed" e "raw params:" raw-params)
        #js {:outcome #js {:outcome "cancelled"}}))))
