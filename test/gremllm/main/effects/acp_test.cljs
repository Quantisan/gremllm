(ns gremllm.main.effects.acp-test
  (:require ["acp" :as acp-module]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.acp :as acp]))

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
                      :kill []})
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
         subprocess #js {:kill (fn [signal]
                                 (swap! calls update :kill conj signal))}
         result #js {:connection conn
                     :subprocess subprocess
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
  "Initialize ACP in test default mode (non-packaged)."
  [on-update]
  (acp/initialize on-update false))

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
                          (acp/shutdown)
                          (done)))))))))

(deftest test-agent-package-mode-policy
  (testing "maps packaging state to ACP agent package mode policy"
    (is (= "latest" (acp/agent-package-mode false)))
    (is (= "cached" (acp/agent-package-mode true)))))

(deftest test-build-npx-agent-package-config
  (testing "exposes buildNpxAgentPackageConfig for test assertions"
    (is (fn? (.. acp-module -__test__ -buildNpxAgentPackageConfig))
        "Expected __test__.buildNpxAgentPackageConfig export"))
  (let [build-npx-agent-package-config (.. acp-module -__test__ -buildNpxAgentPackageConfig)]
    (testing "latest mode uses online latest package"
      (let [^js config (build-npx-agent-package-config "latest")]
        (is (= "npx" (.-command config)))
        (is (= ["--yes"
                "--package=@zed-industries/claude-agent-acp@latest"
                "--"
                "claude-agent-acp"]
               (vec (js->clj (.-args config)))))
        (is (= "true" (.. config -envPatch -npm_config_prefer_online)))))
    (testing "cached mode uses default package and no env patch"
      (let [^js config (build-npx-agent-package-config "cached")]
        (is (= "npx" (.-command config)))
        (is (= ["@zed-industries/claude-agent-acp"]
               (vec (js->clj (.-args config)))))
        (is (= {} (js->clj (.-envPatch config))))))
    (testing "invalid mode falls back to cached behavior"
      (let [^js config (build-npx-agent-package-config "not-a-valid-mode")]
        (is (= ["@zed-industries/claude-agent-acp"]
               (vec (js->clj (.-args config)))))))))

(deftest test-initialize-passes-agent-package-mode
  (testing "forwards agentPackageMode option to create-connection"
    (async done
      (let [{:keys [result]} (make-fake-env)
            captured-opts (atom nil)]
        (with-redefs [acp/create-connection
                      (fn [^js opts]
                        (reset! captured-opts opts)
                        result)]
          (-> (acp/initialize (fn [_] nil) true)
              (.then (fn [_]
                       (is (= "cached" (.-agentPackageMode ^js @captured-opts)))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))

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
          (-> (initialize-dev (fn [_] nil))
              (.then (fn [_] (initialize-dev (fn [_] nil))))
              (.then (fn [_]
                       (is (= 1 @create-count))
                       (acp/shutdown)
                       (is (= ["SIGTERM"] (:kill @calls)))
                       (is (thrown-with-msg? js/Error #"ACP not initialized"
                             (acp/new-session "/tmp/ws")))))
              (.finally (fn []
                          (acp/shutdown)
                          (done)))))))))

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
                        (is (= ["SIGTERM"] (:kill @(:calls failing-env))))
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
                          (acp/shutdown)
                          (done)))))))))

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
