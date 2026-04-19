(ns gremllm.main.effects.acp-integration-test
  (:require ["fs/promises" :as fsp]
            ["os" :as os]
            ["path" :as path]
            [cljs.pprint :as pprint]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.main.effects.acp-trace :as acp-trace]
            [gremllm.schema.codec :as codec]))

(defn- print-updates [updates]
  (println "\n--- Session Updates ---")
  (doseq [{:keys [update]} updates]
    (pprint/pprint update))
  (println "--- End Updates ---"))

(defn- print-permissions [permissions]
  (println "\n--- Permission Requests ---")
  (doseq [p permissions]
    (pprint/pprint p))
  (println (str "--- End Permission Requests (" (count permissions) " total) ---")))

(defn- updates [captured]
  (map :update @captured))

(defn- result-summary [result]
  (when-let [^js res result]
    {:stop-reason (.-stopReason res)}))

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [store    (atom {})
            captured (atom [])
            cwd      (.cwd js/process)]
        (-> (acp/initialize (acp/make-session-update-callback store #(swap! captured conj %)) false)
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id [{:type "text"
                                              :text "Reply with exactly: hi"}])))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (is (pos? (count @captured)))
                     (print-updates @captured)
                     (let [response (->> (updates captured)
                                         (filter #(= :agent-message-chunk (:session-update %)))
                                         (map codec/acp-update-text)
                                         (apply str))]
                       (is (re-find #"(?i)\bhi\b" response)
                           "Expected response to contain 'hi'"))))
            (.catch (fn [err]
                      (is false (str "Live ACP smoke failed: " err))))
            (.finally (fn []
                        (acp/shutdown)
                        (done))))))))

(deftest test-live-read-only
  (testing "read-only prompt: agent reads doc, produces session-update and read events"
    (async done
      (let [store    (atom {})
            recorder (acp-trace/make-recorder)
            tmp-dir  (atom nil)
            doc-path (atom nil)
            result   (atom nil)
            src-path (path/resolve "resources/gremllm-launch-log.md")]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (let [dir (path/join (.tmpdir os) (str "gremllm-test-" (random-uuid)))]
                       (reset! tmp-dir dir)
                       (.mkdir fsp dir #js {:recursive true}))))
            (.then (fn [_]
                     (let [dest (path/join @tmp-dir "gremllm-launch-log.md")]
                       (reset! doc-path dest)
                       (.copyFile fsp src-path dest))))
            (.then (fn [_]
                     (acp/initialize
                       (acp/make-session-update-callback store (:on-update recorder))
                       false
                       (:on-permission recorder)
                       (:on-write recorder)
                       (:on-read recorder))))
            (.then (fn [_] (acp/new-session @tmp-dir)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text "Summarize the first paragraph of the linked document. Do not make any changes to any files."}
                         @doc-path))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (pos? (count @(:events recorder))) "Expected at least one event")
                     (println "\n=== read-only event kinds ===")
                     (doseq [evt @(:events recorder)]
                       (println (str "  +" (:ts evt) "ms " (name (:kind evt)))))
                     (println (str "  read events:       " (count (filter #(= :read (:kind %)) @(:events recorder)))))
                     (println (str "  write events:      " (count (filter #(= :write (:kind %)) @(:events recorder)))))
                     (println (str "  permission events: " (count (filter #(= :permission (:kind %)) @(:events recorder)))))
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "read-only test failed: " err))))
            (.finally (fn []
                        (-> (acp-trace/write-trace!
                              "target/acp-traces"
                              "read-only"
                              {:versions (acp-trace/versions)
                               :prompt   "Summarize the first paragraph..."
                               :doc-path @doc-path
                               :result   (result-summary @result)}
                              (:events recorder))
                            (.then (fn [p] (println "Trace written:" p)))
                            (.catch (fn [e] (js/console.error "Trace write failed" e)))
                            (.finally (fn []
                                        (acp/shutdown)
                                        (when @tmp-dir
                                          (.rm fsp @tmp-dir #js {:recursive true :force true}))
                                        (done)))))))))))

(deftest test-live-write-new-file
  (testing "write-new-file prompt: observe whether Write tool triggers writeTextFile"
    (async done
      (let [store    (atom {})
            recorder (acp-trace/make-recorder)
            tmp-dir  (atom nil)
            result   (atom nil)]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (let [dir (path/join (.tmpdir os) (str "gremllm-test-" (random-uuid)))]
                       (reset! tmp-dir dir)
                       (.mkdir fsp dir #js {:recursive true}))))
            (.then (fn [_]
                     (acp/initialize
                       (acp/make-session-update-callback store (:on-update recorder))
                       false
                       (:on-permission recorder)
                       (:on-write recorder)
                       (:on-read recorder))))
            (.then (fn [_] (acp/new-session @tmp-dir)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       [{:type "text"
                         :text (str "Create a new file called notes.md in the current directory with the single line: hello")}])))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (pos? (count @(:events recorder))) "Expected at least one event")
                     (println "\n=== write-new-file event kinds ===")
                     (doseq [evt @(:events recorder)]
                       (println (str "  +" (:ts evt) "ms " (name (:kind evt)))))
                     (println (str "  write events:      " (count (filter #(= :write (:kind %)) @(:events recorder)))))
                     (println (str "  permission events: " (count (filter #(= :permission (:kind %)) @(:events recorder)))))
                     (let [notes-path (path/join @tmp-dir "notes.md")]
                       (println (str "  notes.md on disk: "
                                     (try
                                       (.accessSync (js/require "fs") notes-path)
                                       "YES — SDK wrote directly"
                                       (catch :default _
                                         "NO — dry-run prevented write")))))
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "write-new-file test failed: " err))))
            (.finally (fn []
                        (-> (acp-trace/write-trace!
                              "target/acp-traces"
                              "write-new-file"
                              {:versions (acp-trace/versions)
                               :prompt   "Create a new file called notes.md..."
                               :result   (result-summary @result)}
                              (:events recorder))
                            (.then (fn [p] (println "Trace written:" p)))
                            (.catch (fn [e] (js/console.error "Trace write failed" e)))
                            (.finally (fn []
                                        (acp/shutdown)
                                        (when @tmp-dir
                                          (.rm fsp @tmp-dir #js {:recursive true :force true}))
                                        (done)))))))))))

(defn- tool-ids [pred updates]
  (->> updates (filter pred) (map :tool-call-id) set))

(defn- tool-statuses [ids updates]
  (let [by-id (->> updates
                   (filter #(= :tool-call-update (:session-update %)))
                   (group-by :tool-call-id))]
    (map (fn [id] (some codec/tool-call-update-status (get by-id id))) ids)))

(defn- assert-diffs-target [updates doc-path]
  (let [diffs (->> updates
                   (filter codec/tool-response-has-diffs?)
                   (mapcat codec/tool-response-diffs))]
    (is (every? #(= doc-path (:path %)) diffs)
        "All diffs should target the linked document")))

(defn- assert-tools-completed [updates]
  (let [read-ids (tool-ids codec/tool-response-read-event? updates)
        diff-ids (tool-ids codec/tool-response-has-diffs? updates)]
    (is (pos? (count read-ids))
        "Expected at least one Read tool-call-update event")
    (is (every? #(= "completed" %) (tool-statuses read-ids updates))
        "All Read tool calls should succeed")
    (is (pos? (count diff-ids))
        "Expected at least one diff from tool-call-update")
    (is (every? #(= "completed" %) (tool-statuses diff-ids updates))
        "All diff-producing tool calls should succeed")))

(def ^:private valid-option-kinds
  #{"allow_always" "allow_once" "reject_once" "reject_always"})

(def ^:private valid-tool-kinds
  #{"read" "edit" "delete" "move" "search" "execute" "think" "fetch" "switch_mode" "other"})

(defn- assert-permission-contract [permissions]
  (is (pos? (count permissions)) "Expected at least one permission request")
  (doseq [p permissions]
    (is (string? (:acp-session-id p)) "permission acp-session-id must be a string")
    (let [tc (:tool-call p)]
      (is (string? (:tool-call-id tc)) "tool-call-id must be a string")
      (when-let [kind (:kind tc)]
        (is (contains? valid-tool-kinds kind) (str "tool kind must be valid enum, got: " kind))))
    (is (pos? (count (:options p))) "options must be non-empty")
    (doseq [opt (:options p)]
      (is (string? (:option-id opt)) "option-id must be a string")
      (is (string? (:name opt)) "option name must be a string")
      (is (contains? valid-option-kinds (:kind opt))
          (str "option kind must be valid enum, got: " (:kind opt))))))

(defn- assert-permission-kinds [permissions]
  ;; The SDK only calls requestPermission for writes/edits.
  ;; Read operations are pre-approved and never reach this callback.
  (let [kinds (set (keep #(get-in % [:tool-call :kind]) permissions))]
    (is (contains? kinds "edit") "Expected at least one 'edit' permission request")))

(defn- assert-write-text-file-called [writes permissions doc-path]
  (is (seq writes)
      (str "writeTextFile not called. Mutating permissions: "
           (pr-str (map #(select-keys (:tool-call %) [:kind :title :raw-input])
                        (remove #(= "read" (get-in % [:tool-call :kind])) permissions)))))
  (when (seq writes)
    (is (every? #(= doc-path (:path %)) writes)
        "All writeTextFile calls should target the linked document")))

(deftest test-live-document-first-edit
  (testing "document-first edit: trace all coerced events, observe (not assert) writeTextFile"
    (async done
      (let [store    (atom {})
            recorder (acp-trace/make-recorder)
            tmp-dir  (atom nil)
            doc-path (atom nil)
            result   (atom nil)
            src-path       (path/resolve "resources/gremllm-launch-log.md")]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (let [dir (path/join (.tmpdir os) (str "gremllm-test-" (random-uuid)))]
                       (reset! tmp-dir dir)
                       (.mkdir fsp dir #js {:recursive true}))))
            (.then (fn [_]
                     (let [dest (path/join @tmp-dir "gremllm-launch-log.md")]
                       (reset! doc-path dest)
                       (.copyFile fsp src-path dest))))
            (.then (fn [_]
                     (acp/initialize
                       (acp/make-session-update-callback store (:on-update recorder))
                       false
                       (:on-permission recorder)
                       (:on-write recorder)
                       (:on-read recorder))))
            (.then (fn [_] (acp/new-session @tmp-dir)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text "Read the linked document. Do not plan or ask questions; just make one edit now: change the title to anything. Do not change anything else."}
                         @doc-path))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (pos? (count @(:events recorder))) "Expected at least one event")
                     (println "\n=== document-first-edit event kinds ===")
                     (doseq [evt @(:events recorder)]
                       (println (str "  +" (:ts evt) "ms " (name (:kind evt)))))
                     (println (str "  write events: " (count (filter #(= :write (:kind %)) @(:events recorder)))))
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "document-first-edit test failed: " err))))
            (.finally (fn []
                        (-> (acp-trace/write-trace!
                              "target/acp-traces"
                              "document-first-edit"
                              {:versions (acp-trace/versions)
                               :prompt   "...change the title to anything..."
                               :doc-path @doc-path
                               :result   (result-summary @result)}
                              (:events recorder))
                            (.then (fn [p] (println "Trace written:" p)))
                            (.catch (fn [e] (js/console.error "Trace write failed" e)))
                            (.finally (fn []
                                        (acp/shutdown)
                                        (when @tmp-dir
                                          (.rm fsp @tmp-dir #js {:recursive true :force true}))
                                        (done)))))))))))
