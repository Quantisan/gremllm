(ns gremllm.main.effects.acp-integration-test
  (:require ["fs/promises" :as fsp]
            ["path" :as path]
            [cljs.pprint :as pprint]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.schema.codec :as codec]))

(defn- print-updates [updates]
  (println "\n--- Session Updates ---")
  (doseq [{:keys [update]} updates]
    (pprint/pprint update))
  (println "--- End Updates ---"))

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
                     (let [response (->> @captured
                                         (map :update)
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

(deftest test-live-document-first-edit
  (testing "resource_link prompt: agent reads doc, proposes diff, file unchanged"
    (async done
      (let [store    (atom {})
            captured (atom [])
            cwd      (.cwd js/process)
            doc-path (path/resolve "resources/gremllm-launch-log.md")]
        (-> (.readFile fsp doc-path "utf8")
            (.then (fn [content-before]
                     (-> (acp/initialize (acp/make-session-update-callback store #(swap! captured conj %)) false)
                         (.then (fn [_] (acp/new-session cwd)))
                         (.then (fn [session-id]
                                  (acp/prompt session-id
                                    (acp-actions/prompt-content-blocks
                                      "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else."
                                      doc-path))))
                         (.then (fn [^js result]
                                  (is (= "end_turn" (.-stopReason result)))
                                  (print-updates @captured)
                                  (let [diffs (->> @captured
                                                   (map :update)
                                                   (filter codec/tool-response-has-diffs?)
                                                   (mapcat codec/tool-response-diffs))]
                                    (is (pos? (count diffs))
                                        "Expected at least one diff from tool-call-update")
                                    (is (every? #(= doc-path (:path %)) diffs)
                                        "All diffs should target the linked document"))
                                  (let [updates-by-id (->> @captured
                                                           (map :update)
                                                           (filter #(= :tool-call-update (:session-update %)))
                                                           (group-by :tool-call-id))
                                        read-tool-ids (set (map :tool-call-id
                                                                 (filter codec/tool-response-read-event?
                                                                         (map :update @captured))))
                                        read-statuses (->> read-tool-ids
                                                           (map (fn [id] (some codec/tool-call-update-status (get updates-by-id id)))))
                                        diff-tool-ids (set (map :tool-call-id (filter codec/tool-response-has-diffs? (map :update @captured))))
                                        diff-statuses      (->> diff-tool-ids
                                                                (map (fn [id] (some codec/tool-call-update-status (get updates-by-id id)))))]
                                    (is (pos? (count read-tool-ids))
                                        "Expected at least one Read tool-call-update event")
                                    (is (every? #(= "completed" %) read-statuses)
                                        "All Read tool calls should succeed")
                                    (is (every? #(= "completed" %) diff-statuses)
                                        "All diff-producing tool calls should succeed"))))
                         (.then (fn [_] (.readFile fsp doc-path "utf8")))
                         (.then (fn [content-after]
                                  (is (= content-before content-after)
                                      "Document must be unchanged (writeTextFile is a no-op)")))
                         (.catch (fn [err]
                                   (is false (str "Document-first edit test failed: " err))))
                         (.finally (fn []
                                     (acp/shutdown)
                                     (done)))))))))))
