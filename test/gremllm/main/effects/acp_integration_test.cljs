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
            [gremllm.schema.codec.acp :as acp-codec]))

(defn- print-updates [updates]
  (println "\n--- Session Updates ---")
  (doseq [{:keys [update]} updates]
    (pprint/pprint update))
  (println "--- End Updates ---"))

(defn- updates [captured]
  (map :update @captured))

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

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [store    (atom {})
            captured (atom [])
            cwd      (.cwd js/process)]
        (-> (acp/initialize {:on-session-update (acp/make-session-update-callback store #(swap! captured conj %))})
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id [{:type "text"
                                              :text "Create a mathematical model describing gremlins behaviour."}])))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (is (pos? (count @captured)))
                     (print-updates @captured)
                     (let [response (->> (updates captured)
                                         (filter #(= :agent-message-chunk (:session-update %)))
                                         (map acp-codec/acp-update-text)
                                         (apply str))
                           thoughts (->> (updates captured)
                                         (filter #(= :agent-thought-chunk (:session-update %)))
                                         (map acp-codec/acp-update-text))]
                       (is (> (count response) 200)
                           "Expected a substantive response (>200 chars) to a reasoning-heavy prompt")
                       (is (pos? (count thoughts))
                           "Expected at least one :agent-thought-chunk (thinking enabled in session-meta)")
                       (is (> (count (apply str thoughts)) 100)
                           "Expected :agent-thought-chunk text to be non-empty."))))
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
                     (with-redefs [acp/read-text-file (make-read-recorder (:on-read recorder))]
                       (acp/initialize
                         {:on-session-update (acp/make-session-update-callback store (:on-session-update recorder))
                          :on-permission     (:on-permission recorder)
                          :on-write          (:on-write recorder)}))))
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
                     (with-redefs [acp/read-text-file (make-read-recorder (:on-read recorder))]
                       (acp/initialize
                         {:on-session-update (acp/make-session-update-callback store (:on-session-update recorder))
                          :on-permission     (:on-permission recorder)
                          :on-write          (:on-write recorder)}))))
            (.then (fn [_] (acp/new-session @tmp-dir)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       [{:type "text"
                         :text "Create a new file called notes.md in the current directory with the single line: hello"}])))
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
                     (with-redefs [acp/read-text-file (make-read-recorder (:on-read recorder))]
                       (acp/initialize
                         {:on-session-update (acp/make-session-update-callback store (:on-session-update recorder))
                          :on-permission     (:on-permission recorder)
                          :on-write          (:on-write recorder)}))))
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
