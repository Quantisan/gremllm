(ns gremllm.main.effects.acp-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.acp :as acp]))

(defn- make-fake-env
  "Creates a fake ACP connection environment with call-tracking atoms.
   Returns {:calls atom, :result js-obj} where calls tracks method invocations
   and result is the JS object returned by the fake create-connection."
  []
  (let [calls (atom {:initialize []
                     :new-session []
                     :resume-session []
                     :prompt []
                     :kill []})
        conn  #js {:initialize
                   (fn [payload]
                     (swap! calls update :initialize conj payload)
                     (js/Promise.resolve #js {:agentCapabilities #js {}}))
                   :newSession
                   (fn [payload]
                     (swap! calls update :new-session conj payload)
                     (js/Promise.resolve #js {:sessionId "s-123"}))
                   :unstable_resumeSession
                   (fn [payload]
                     (swap! calls update :resume-session conj payload)
                     (js/Promise.resolve #js {}))
                   :prompt
                   (fn [payload]
                     (swap! calls update :prompt conj payload)
                     (js/Promise.resolve #js {:stopReason "end_turn"}))}
        subprocess #js {:kill (fn [signal]
                                (swap! calls update :kill conj signal))}
        result #js {:connection conn
                    :subprocess subprocess
                    :protocolVersion "test-protocol"}]
    {:calls calls
     :result result}))

(deftest test-initialize-wiring
  (testing "passes client info and callback to connection"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            captured-callback (atom nil)]
        (with-redefs [acp/create-connection
                      (fn [opts]
                        (reset! captured-callback (.-onSessionUpdate opts))
                        result)]
          (-> (acp/initialize (fn [_] nil))
              (.then (fn [_]
                       (is (fn? @captured-callback))
                       (let [payload (first (:initialize @calls))]
                         (is (= "test-protocol" (.-protocolVersion payload)))
                         (is (= "gremllm" (.. payload -clientInfo -name)))
                         (is (= "Gremllm" (.. payload -clientInfo -title)))
                         (is (= "0.1.0" (.. payload -clientInfo -version)))
                         (is (= false (.. payload -clientCapabilities -terminal))))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))
