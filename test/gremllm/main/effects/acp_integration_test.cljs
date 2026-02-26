(ns gremllm.main.effects.acp-integration-test
  (:require ["path" :as path]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.schema.codec :as codec]))

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [updates (atom [])
            cwd (.cwd js/process)]
        (-> (acp/initialize #(swap! updates conj %) false)
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id [{:type "text"
                                              :text "Reply with exactly: hi"}])))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (is (pos? (count @updates)))))
            (.catch (fn [err]
                      (is false (str "Live ACP smoke failed: " err))))
            (.finally (fn []
                        (acp/shutdown)
                        (done))))))))

(deftest test-live-document-first-edit
  (testing "resource_link prompt produces tool-call-update with diffs"
    (async done
      (let [updates  (atom [])
            cwd      (.cwd js/process)
            doc-path (path/resolve "resources/gremllm-launch-log.md")]
        (-> (acp/initialize #(swap! updates conj %) false)
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (is (string? session-id))
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else."
                         doc-path))))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (let [coerced (map codec/acp-session-update-from-js @updates)]
                       (is (some #(codec/has-diffs? (:update %)) coerced)
                           "Expected at least one tool-call-update with diff content"))))
            (.catch (fn [err]
                      (is false (str "Document-first edit test failed: " err))))
            (.finally (fn []
                        (acp/shutdown)
                        (done))))))))
