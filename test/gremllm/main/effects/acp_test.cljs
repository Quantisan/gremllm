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
                      (fn [^js opts]
                        (reset! captured-callback (.-onSessionUpdate opts))
                        result)]
          (-> (acp/initialize (fn [_] nil))
              (.then (fn [_]
                       (is (fn? @captured-callback))
                       (let [^js payload (first (:initialize @calls))]
                         (is (= "test-protocol" (.-protocolVersion payload)))
                         (is (= "gremllm" (.. payload -clientInfo -name)))
                         (is (= "Gremllm" (.. payload -clientInfo -title)))
                         (is (= "0.1.0" (.. payload -clientInfo -version)))
                         (is (= false (.. payload -clientCapabilities -terminal))))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))

(deftest test-session-and-prompt-delegation
  (testing "delegates new-session, resume-session, and prompt to connection"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            cwd "/tmp/ws"]
        (with-redefs [acp/create-connection (fn [_] result)]
          (-> (acp/initialize (fn [_] nil))
              (.then (fn [_] (acp/new-session cwd)))
              (.then (fn [session-id]
                       (is (= "s-123" session-id))
                       (acp/resume-session cwd session-id)))
              (.then (fn [resume-id]
                       (is (= "s-123" resume-id))
                       (acp/prompt resume-id [{:type "text"
                                               :text "Say only: Hello"}])))
              (.then (fn [^js prompt-result]
                       (is (= "end_turn" (.-stopReason prompt-result)))
                       (let [^js new-session-arg (first (:new-session @calls))
                             ^js resume-arg (first (:resume-session @calls))
                             ^js prompt-arg (first (:prompt @calls))]
                         (is (= cwd (.-cwd new-session-arg)))
                         (is (= 0 (alength (.-mcpServers new-session-arg))))
                         (is (= "s-123" (.-sessionId resume-arg)))
                         (is (= cwd (.-cwd resume-arg)))
                         (is (= "s-123" (.-sessionId prompt-arg)))
                         (let [^js first-block (aget (.-prompt prompt-arg) 0)]
                           (is (= "text" (.-type first-block)))
                           (is (= "Say only: Hello" (.-text first-block)))))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))

(deftest test-lifecycle-guardrails
  (testing "double initialize is idempotent, shutdown kills subprocess, post-shutdown throws"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            create-count (atom 0)]
        (with-redefs [acp/create-connection
                      (fn [_]
                        (swap! create-count inc)
                        result)]
          (-> (acp/initialize (fn [_] nil))
              (.then (fn [_] (acp/initialize (fn [_] nil))))
              (.then (fn [_]
                       (is (= 1 @create-count))
                       (acp/shutdown)
                       (is (= ["SIGTERM"] (:kill @calls)))
                       (is (thrown-with-msg? js/Error #"ACP not initialized"
                             (acp/new-session "/tmp/ws")))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))
