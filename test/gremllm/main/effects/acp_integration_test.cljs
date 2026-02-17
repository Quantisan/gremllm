(ns gremllm.main.effects.acp-integration-test
  (:require [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.acp :as acp]))

(deftest test-live-acp-happy-path
  (testing "initialize, create session, prompt, and receive updates"
    (async done
      (let [updates (atom [])
            cwd (.cwd js/process)]
        (-> (acp/initialize #(swap! updates conj %))
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
