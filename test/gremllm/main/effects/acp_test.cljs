(ns gremllm.main.effects.acp-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.acp :as acp]
            [gremllm.schema.codec :as codec]))

(defn- make-fake-env
  "Creates a fake ACP connection environment with call-tracking atoms.
   Returns {:calls atom, :result js-obj} where calls tracks method invocations
   and result is the JS object returned by the fake create-connection."
  ([] (make-fake-env {}))
  ([{:keys [initialize-result session-id]
     :or {initialize-result (js/Promise.resolve #js {:agentCapabilities #js {}})
          session-id "s-123"}}]
   (let [calls (atom {:initialize []
                      :new-session []
                      :resume-session []
                      :prompt []
                      :dispose-count 0})
         conn  #js {:initialize
                    (fn [payload]
                      (swap! calls update :initialize conj payload)
                      initialize-result)
                    :newSession
                    (fn [payload]
                      (swap! calls update :new-session conj payload)
                      (js/Promise.resolve #js {:sessionId session-id}))
                    :unstable_resumeSession
                    (fn [payload]
                      (swap! calls update :resume-session conj payload)
                      (js/Promise.resolve #js {}))
                    :prompt
                    (fn [payload]
                      (swap! calls update :prompt conj payload)
                      (js/Promise.resolve #js {:stopReason "end_turn"}))}
         dispose-agent (fn []
                         (swap! calls update :dispose-count inc)
                         (js/Promise.resolve nil))
         result #js {:connection conn
                     :disposeAgent dispose-agent
                     :protocolVersion "test-protocol"}]
     {:calls calls
      :result result})))

(defn- make-rotating-create
  "Returns a fake create-connection fn that yields successive env :result values.
   Also returns a :create-count atom for assertions."
  [envs]
  (let [create-count (atom 0)]
    {:create-count create-count
     :fake-create  (fn [_]
                     (let [i (swap! create-count inc)]
                       (:result (nth envs (dec i)))))}))

(defn- initialize-dev
  "Initialize ACP in test default mode."
  [on-update]
  (acp/initialize {:on-session-update on-update}))

(deftest test-initialize-wiring
  (testing "passes client info and callback to connection"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            captured-callback (atom nil)]
        (with-redefs [acp/create-connection
                      (fn [^js opts]
                        (reset! captured-callback (.-onSessionUpdate opts))
                        result)]
          (-> (initialize-dev (fn [_] nil))
              (.then (fn [_]
                       (is (fn? @captured-callback))
                       (let [^js payload (first (:initialize @calls))]
                         (is (= "test-protocol" (.-protocolVersion payload)))
                         (is (= "gremllm" (.. payload -clientInfo -name)))
                         (is (= "Gremllm" (.. payload -clientInfo -title)))
                         (is (= "0.1.0" (.. payload -clientInfo -version)))
                         (is (= false (.. payload -clientCapabilities -terminal))))))
              (.finally (fn []
                          (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-callback-fires-and-coerces-diffs
  (testing "onSessionUpdate callback receives raw JS, coerces to CLJS, tool-response-has-diffs? true"
    (async done
      (let [received    (atom nil)
            captured-cb (atom nil)
            {:keys [result]} (make-fake-env)]
        (with-redefs [acp/create-connection
                      (fn [^js opts]
                        (reset! captured-cb (.-onSessionUpdate opts))
                        result)]
          (-> (initialize-dev (fn [data] (reset! received data)))
              (.then (fn [_]
                       (let [js-data #js {:sessionId "s-test"
                                          :update    #js {:sessionUpdate "tool_call_update"
                                                          :toolCallId    "tc-1"
                                                          :content       #js [#js {:type    "diff"
                                                                                   :path    "/doc.md"
                                                                                   :oldText "old"
                                                                                   :newText "new"}]}}]
                         (@captured-cb js-data)
                         (is (identical? js-data @received))
                         (let [coerced (codec/acp-session-update-from-js @received)]
                           (is (= "s-test" (:acp-session-id coerced)))
                           (is (codec/tool-response-has-diffs? (:update coerced)))))))
              (.finally (fn []
                          (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-session-and-prompt-delegation
  (testing "delegates new-session, resume-session, and prompt to connection"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            cwd "/tmp/ws"]
        (with-redefs [acp/create-connection (fn [_] result)]
          (-> (initialize-dev (fn [_] nil))
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
                          (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-lifecycle-guardrails
  (testing "double initialize is idempotent, shutdown disposes agent, post-shutdown throws"
    (async done
      (let [{:keys [calls result]} (make-fake-env)
            create-count (atom 0)]
        (with-redefs [acp/create-connection
                      (fn [_]
                        (swap! create-count inc)
                        result)]
          (-> (initialize-dev (fn [_] nil))
              (.then (fn [_] (initialize-dev (fn [_] nil))))
              (.then (fn [_]
                       (is (= 1 @create-count))
                       (acp/shutdown)
                       (is (= 1 (:dispose-count @calls)))
                       (is (thrown-with-msg? js/Error #"ACP not initialized"
                             (acp/new-session "/tmp/ws")))))
              (.finally (fn []
                          (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-initialize-failure-does-not-poison-state
  (testing "failed initialize cleans up and allows retry"
    (async done
      (let [failing-env  (make-fake-env {:initialize-result
                                         (js/Promise.reject (js/Error. "init failed"))})
            recovery-env (make-fake-env {:session-id "s-456"})
            {:keys [create-count fake-create]}
            (make-rotating-create [failing-env recovery-env])]
        (with-redefs [acp/create-connection fake-create]
          (-> (initialize-dev (fn [_] nil))
              (.then (fn [_]
                       (is false "Expected first initialize call to fail")))
              (.catch (fn [err]
                        (is (= "init failed" (.-message err)))
                        (is (= 1 (:dispose-count @(:calls failing-env))))
                        (is (thrown-with-msg? js/Error #"ACP not initialized"
                              (acp/new-session "/tmp/ws")))
                        ;; Re-bind for retry because async Promise callbacks run
                        ;; after the outer with-redefs scope has unwound.
                        (with-redefs [acp/create-connection fake-create]
                          (initialize-dev (fn [_] nil)))))
              (.then (fn [_] (acp/new-session "/tmp/ws")))
              (.then (fn [session-id]
                       (is (= "s-456" session-id))
                       (is (= 2 @create-count))))
              (.finally (fn []
                          (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-slice-content-by-lines
  (let [content "line-1\nline-2\nline-3\nline-4\n"]
    (testing "slices from 1-indexed line with limit"
      (is (= "line-2\nline-3" (acp/slice-content-by-lines content 2 2))))
    (testing "slices from line to end when no limit"
      (is (= "line-3\nline-4\n" (acp/slice-content-by-lines content 3 nil))))))

(deftest test-read-text-file
  (testing "reads file and returns content in expected shape"
    (async done
      (let [os   (js/require "os")
            fs   (js/require "fs/promises")
            path (js/require "path")]
        (-> (.mkdtemp fs (.join path (.tmpdir os) "acp-read-test-"))
            (.then (fn [dir]
                     (let [file-path (.join path dir "test.md")
                           content   "line-1\nline-2\nline-3\n"]
                       (-> (.writeFile fs file-path content "utf8")
                           (.then (fn [_] (acp/read-text-file #js {:path file-path})))
                           (.then (fn [^js result]
                                    (is (= content (.-content result)))))
                           (.finally (fn []
                                       (.rm fs dir #js {:recursive true
                                                        :force true})))))))
            (.finally (fn [] (done))))))))

;; --- Step 4a: async dispose-promise chaining ---

(deftest test-start-connection-catch-chains-rethrow-after-dispose
  (testing "initialization failure: error only propagates after dispose promise settles"
    (async done
      (let [events          (atom [])
            resolve-dispose (atom nil)
            deferred        (js/Promise. (fn [r _] (reset! resolve-dispose r)))
            dispose-agent   (fn []
                              (swap! events conj :dispose-called)
                              (.then deferred (fn [_] (swap! events conj :dispose-settled))))
            conn            #js {:initialize (fn [_] (js/Promise.reject (js/Error. "boom")))}
            result          #js {:connection   conn
                                 :disposeAgent dispose-agent
                                 :protocolVersion "v"}]
        ;; Resolve the deferred dispose after current sync + microtask work completes
        (js/setTimeout (fn [] (@resolve-dispose nil)) 0)
        (with-redefs [acp/create-connection (fn [_] result)]
          (-> (initialize-dev (fn [_] nil))
              (.catch (fn [err]
                        (is (= "boom" (.-message err)))
                        (is (= [:dispose-called :dispose-settled] @events)
                            "rethrow should chain after dispose settles")))
              (.finally (fn [] (.then (acp/shutdown) (fn [_] (done)))))))))))

(deftest test-shutdown-returns-promise-that-awaits-dispose
  (testing "shutdown returns a promise that resolves after dispose settles"
    (async done
      (let [events          (atom [])
            resolve-dispose (atom nil)
            deferred        (js/Promise. (fn [r _] (reset! resolve-dispose r)))
            conn            #js {:initialize
                                 (fn [_] (js/Promise.resolve #js {:agentCapabilities #js {}}))}
            dispose-agent   (fn []
                              (.then deferred (fn [_] (swap! events conj :dispose-settled))))
            result          #js {:connection   conn
                                 :disposeAgent dispose-agent
                                 :protocolVersion "v"}]
        (js/setTimeout (fn [] (@resolve-dispose nil)) 0)
        (with-redefs [acp/create-connection (fn [_] result)]
          (-> (initialize-dev (fn [_] nil))
              (.then (fn [_]
                       (let [p (acp/shutdown)]
                         (is (instance? js/Promise p)
                             "shutdown should return a promise")
                         (-> p
                             (.then (fn [_]
                                      (is (= [:dispose-settled] @events)
                                          "dispose should have settled before shutdown promise resolves")))
                             (.finally (fn [] (done)))))))
              (.catch (fn [e] (js/console.error "unexpected error" e) (done)))))))))

