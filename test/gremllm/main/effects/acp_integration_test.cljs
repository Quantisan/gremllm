(ns gremllm.main.effects.acp-integration-test
  (:require ["fs/promises" :as fsp]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.main.effects.acp-trace :as acp-trace]
            [gremllm.main.effects.acp.permission :as acp-permission]
            [gremllm.schema.codec.acp :as acp-codec]))

(defn- result-summary [result]
  (when-let [^js res result]
    {:stop-reason (.-stopReason res)}))

(defn- make-read-recorder
  [on-read]
  (let [delegate acp/read-text-file]
    (fn [^js params]
      (on-read {:path  (.-path params)
                :line  (.-line params)
                :limit (.-limit params)})
      (delegate params))))

(defn- live-acp-context
  [{:keys [copy-doc? on-awaiting-user-decision]}]
  (let [tmp-dir  (path/join (.tmpdir os) (str "gremllm-test-" (random-uuid)))
        src-path (path/resolve "resources/gremllm-launch-log.md")]
    {:store                      (atom {})
     :recorder                   (acp-trace/make-recorder)
     :tmp-dir                    tmp-dir
     :src-path                   src-path
     :doc-path                   (when copy-doc?
                                   (path/join tmp-dir "gremllm-launch-log.md"))
     :on-awaiting-user-decision  on-awaiting-user-decision}))

(defn- initialize-recorded-acp!
  [{:keys [store recorder on-awaiting-user-decision]}]
  (with-redefs [acp/read-text-file (make-read-recorder (:on-read recorder))]
    (acp/initialize
      {:on-session-update          (acp/make-session-update-callback store (:on-session-update recorder))
       :on-permission-request      (:on-permission recorder)
       :on-awaiting-user-decision  on-awaiting-user-decision})))

(defn- setup-live-acp!
  [{:keys [tmp-dir src-path doc-path] :as ctx}]
  (-> (.mkdir fsp tmp-dir #js {:recursive true})
      (.then (fn [_]
               (when doc-path
                 (.copyFile fsp src-path doc-path))))
      (.then (fn [_] (initialize-recorded-acp! ctx)))
      (.then (fn [_] ctx))))

(defn- print-event-summary!
  [scenario recorder kinds]
  (println (str "\n=== " scenario " event kinds ==="))
  (doseq [evt @(:events recorder)]
    (println (str "  +" (:ts evt) "ms " (name (:kind evt)))))
  (doseq [kind kinds]
    (println (str "  " (name kind) " events: "
                  (count (filter #(= kind (:kind %)) @(:events recorder)))))))

(defn- trace-metadata
  [ctx result trace-prompt]
  (cond-> {:versions (acp-trace/versions)
           :prompt   trace-prompt
           :result   (result-summary @result)}
    (:doc-path ctx) (assoc :doc-path (:doc-path ctx))))

(defn- remove-temp-dir!
  [tmp-dir]
  (if tmp-dir
    (.rm fsp tmp-dir #js {:recursive true :force true})
    (js/Promise.resolve nil)))

(defn- finish-live-acp!
  [{:keys [tmp-dir recorder] :as ctx} result {:keys [scenario prompt done]}]
  (-> (acp-trace/write-trace!
        "target/acp-traces"
        scenario
        (trace-metadata ctx result prompt)
        (:events recorder))
      (.then (fn [p] (println "Trace written:" p)))
      (.catch (fn [e] (js/console.error "Trace write failed" e)))
      (.finally
        (fn []
          (-> (acp/shutdown)
              (.catch (fn [e] (js/console.error "ACP shutdown failed" e)))
              (.then (fn [_] (remove-temp-dir! tmp-dir)))
              (.catch (fn [e] (js/console.error "Temp cleanup failed" e)))
              (.finally done))))))

(defn- session-updates [ctx]
  (->> @(-> ctx :recorder :events)
       (filter #(= :session-update (:kind %)))
       (map (comp :update :payload))))

(defn- permission-event-count [ctx]
  (->> @(-> ctx :recorder :events)
       (filter #(= :permission (:kind %)))
       count))

(defn- session-pinned-mode
  "The most recent permission mode the session reported via config-option-update."
  [ctx]
  (->> (session-updates ctx)
       (keep acp-codec/config-update-mode)
       last))

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [ctx    (live-acp-context nil)
            result (atom nil)]
        (-> (setup-live-acp! ctx)
            (.then (fn [_] (acp/new-session (:tmp-dir ctx))))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id
                       [{:type "text"
                         :text "Create a mathematical model describing gremlins behaviour."}])))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (let [updates  (session-updates ctx)
                           response (->> updates
                                         (filter #(= :agent-message-chunk (:session-update %)))
                                         (map acp-codec/acp-update-text)
                                         (apply str))
                           thoughts (->> updates
                                         (filter #(= :agent-thought-chunk (:session-update %)))
                                         (map acp-codec/acp-update-text))]
                       (is (> (count response) 200)
                           "Expected a substantive response (>200 chars) to a reasoning-heavy prompt")
                       (is (pos? (count thoughts))
                           "Expected at least one :agent-thought-chunk (thinking enabled in session-meta)")
                       (is (> (count (apply str thoughts)) 100)
                           "Expected :agent-thought-chunk text to be non-empty."))
                     (print-event-summary! "happy-path" (:recorder ctx) [:session-update])
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "Live ACP smoke failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "happy-path"
                                                      :prompt   "Create a mathematical model describing gremlins behaviour."
                                                      :done     done}))))))))

(deftest test-live-read-only
  (testing "read-only prompt: agent reads doc, produces session-update and read events"
    (async done
      (let [ctx    (live-acp-context {:copy-doc? true})
            result (atom nil)]
        (-> (setup-live-acp! ctx)
            (.then (fn [_] (acp/new-session (:tmp-dir ctx))))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text "Summarize the first paragraph of the linked document. Do not make any changes to any files."}
                         (:doc-path ctx)))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (pos? (count @(-> ctx :recorder :events))) "Expected at least one event")
                     (print-event-summary! "read-only" (:recorder ctx) [:read :permission])
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "read-only test failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "read-only"
                                                      :prompt   "Summarize the first paragraph..."
                                                      :done     done}))))))))

(defn- pick-option-id
  "Find the option-id whose :kind matches the given kind in an enriched
   permission request's :options vector. Mirrors renderer's option-id-for-kind."
  [enriched kind]
  (->> (:options enriched)
       (some (fn [opt] (when (= kind (:kind opt)) (:option-id opt))))))

(defn- make-decider
  "Build an :on-awaiting-user-decision callback that captures each enriched request
   into the given atom, then synchronously resolves it with the option-id matching
   decision-kind. The resolver is registered before this callback fires,
   so synchronous resolution is safe."
  [decision-kind captured]
  (fn [enriched]
    (swap! captured conj enriched)
    (let [tool-call-id (get-in enriched [:tool-call :tool-call-id])
          option-id    (pick-option-id enriched decision-kind)]
      (when option-id
        (acp-permission/record-decision! tool-call-id option-id)))))

(def ^:private edit-prompt
  "Read the linked document. Do not plan or ask questions; just make one edit now: change the title to anything. Do not change anything else.")

(deftest test-live-edit-accept
  (testing "domain workflow: agent requests Edit, user accepts, fs write occurs"
    (async done
      (let [captured (atom [])
            ctx      (live-acp-context {:copy-doc?             true
                                        :on-awaiting-user-decision (make-decider "allow_once" captured)})
            result   (atom nil)]
        (-> (setup-live-acp! ctx)
            (.then (fn [_] (acp/new-session (:tmp-dir ctx))))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text edit-prompt}
                         (:doc-path ctx)))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (= "default" (session-pinned-mode ctx))
                         "Session must be pinned to 'default' permission mode or the edit gate is bypassed")
                     (is (pos? (permission-event-count ctx))
                         "Edit permission gate bypassed — agent auto-approved without requesting permission (session not in 'default' mode?)")
                     (let [edit-perms (->> @captured
                                           (filter #(= "edit" (get-in % [:tool-call :kind]))))
                           edit-perm  (first edit-perms)
                           diffs      (->> (get-in edit-perm [:tool-call :content])
                                           (filter #(= "diff" (:type %))))
                           opt-kinds  (set (map :kind (:options edit-perm)))]
                       (is (seq edit-perms)
                           "Expected at least one pending-permission for an edit tool call")
                       (is (seq diffs)
                           "Expected diff content in the captured edit permission")
                       (is (every? #(str/starts-with? (:path %) (:tmp-dir ctx)) diffs)
                           "Expected every diff path to be inside the test tmp-dir")
                       (is (contains? opt-kinds "allow_once")
                           "Expected allow_once option in the permission request")
                       (is (contains? opt-kinds "reject_once")
                           "Expected reject_once option in the permission request"))
                     (print-event-summary! "edit-accept" (:recorder ctx) [:permission])
                     (println "=== end ===")
                     (js/Promise.all #js [(.readFile fsp (:src-path ctx) "utf8")
                                          (.readFile fsp (:doc-path ctx) "utf8")])))
            (.then (fn [^js contents]
                     (is (not= (aget contents 0) (aget contents 1))
                         "Expected document.md on disk to differ from source after accept.")))
            (.catch (fn [err]
                      (is false (str "edit-accept test failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "edit-accept"
                                                      :prompt   edit-prompt
                                                      :done     done}))))))))

(deftest test-live-edit-reject
  (testing "domain workflow: agent requests Edit, user rejects, fs write suppressed"
    (async done
      (let [captured (atom [])
            ctx      (live-acp-context {:copy-doc?             true
                                        :on-awaiting-user-decision (make-decider "reject_once" captured)})
            result   (atom nil)]
        (-> (setup-live-acp! ctx)
            (.then (fn [_] (acp/new-session (:tmp-dir ctx))))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text edit-prompt}
                         (:doc-path ctx)))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "default" (session-pinned-mode ctx))
                         "Session must be pinned to 'default' permission mode or the edit gate is bypassed")
                     (is (pos? (permission-event-count ctx))
                         "Edit permission gate bypassed — agent auto-approved without requesting permission (session not in 'default' mode?)")
                     (is (some #(= "edit" (get-in % [:tool-call :kind])) @captured)
                         "Expected at least one pending-permission for an edit tool call (subsumes the dropped guide-rail).")
                     (print-event-summary! "edit-reject" (:recorder ctx) [:permission])
                     (println "=== end ===")
                     ;; disallowedTools blocks MultiEdit/NotebookEdit; this comparison
                     ;; also catches Bash-based circumvention.
                     (js/Promise.all #js [(.readFile fsp (:src-path ctx) "utf8")
                                          (.readFile fsp (:doc-path ctx) "utf8")])))
            (.then (fn [^js contents]
                     (is (= (aget contents 0) (aget contents 1))
                         "Expected document.md on disk to be unchanged after reject.")))
            (.catch (fn [err]
                      (is false (str "edit-reject test failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "edit-reject"
                                                      :prompt   edit-prompt
                                                      :done     done}))))))))
