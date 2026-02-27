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

(defn- updates [captured]
  (map :update @captured))

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

(deftest test-live-document-first-edit
  (testing "resource_link prompt: agent reads doc, proposes diff, file unchanged"
    (async done
      (let [store         (atom {})
            captured      (atom [])
            content-before (atom nil)
            cwd           (.cwd js/process)
            doc-path      (path/resolve "resources/gremllm-launch-log.md")]
        (-> (.readFile fsp doc-path "utf8")
            (.then (fn [content] (reset! content-before content)))
            (.then (fn [_] (acp/initialize (acp/make-session-update-callback store #(swap! captured conj %)) false)))
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else."
                         doc-path))))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (print-updates @captured)
                     (let [evts (updates captured)]
                       (assert-diffs-target evts doc-path)
                       (assert-tools-completed evts))))
            (.then (fn [_] (.readFile fsp doc-path "utf8")))
            (.then (fn [content-after]
                     (is (= @content-before content-after)
                         "Document must be unchanged (writeTextFile is a no-op)")))
            (.catch (fn [err]
                      (is false (str "Document-first edit test failed: " err))))
            (.finally (fn []
                        (acp/shutdown)
                        (done))))))))
