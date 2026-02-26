(ns gremllm.main.effects.acp-integration-test
  (:require ["fs/promises" :as fsp]
            ["path" :as path]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.schema.codec :as codec]
            [nexus.registry :as nxr]))

;; Replace the IPC send effect with a no-op so tests don't need a renderer.
;; Individual tests capture updates via the session-update callback instead.
(nxr/register-effect! :ipc.effects/send-to-renderer
  (fn [_ctx _store _channel _data]))

(defn- make-test-callback [store captured]
  (fn [params]
    (try
      (let [coerced (codec/acp-session-update-from-js params)]
        (swap! captured conj coerced)
        (nxr/dispatch store {} [[:acp.events/session-update coerced]]))
      (catch :default e
        (js/console.error "Test ACP session update coercion failed" e params)))))

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [store    (atom {})
            captured (atom [])
            cwd      (.cwd js/process)]
        (-> (acp/initialize (make-test-callback store captured) false)
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id [{:type "text"
                                              :text "Reply with exactly: hi"}])))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (is (pos? (count @captured)))
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
                     (-> (acp/initialize (make-test-callback store captured) false)
                         (.then (fn [_] (acp/new-session cwd)))
                         (.then (fn [session-id]
                                  (acp/prompt session-id
                                    (acp-actions/prompt-content-blocks
                                      "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else."
                                      doc-path))))
                         (.then (fn [^js result]
                                  (is (= "end_turn" (.-stopReason result)))
                                  (let [diffs (->> @captured
                                                   (map :update)
                                                   (filter codec/has-diffs?)
                                                   (mapcat codec/acp-pending-diffs))]
                                    (is (pos? (count diffs))
                                        "Expected at least one diff from tool-call-update")
                                    (is (every? #(= doc-path (:path %)) diffs)
                                        "All diffs should target the linked document"))))
                         (.then (fn [_] (.readFile fsp doc-path "utf8")))
                         (.then (fn [content-after]
                                  (is (= content-before content-after)
                                      "Document must be unchanged (writeTextFile is a no-op)")))
                         (.catch (fn [err]
                                   (is false (str "Document-first edit test failed: " err))))
                         (.finally (fn []
                                     (acp/shutdown)
                                     (done)))))))))))
