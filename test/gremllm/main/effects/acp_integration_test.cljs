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

(defn- print-permissions [permissions]
  (println "\n--- Permission Requests ---")
  (doseq [p permissions]
    (pprint/pprint p))
  (println (str "--- End Permission Requests (" (count permissions) " total) ---")))

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

(deftest test-live-document-first-edit
  (testing "resource_link prompt: agent reads doc, proposes diff, file unchanged"
    (async done
      (let [store         (atom {})
            captured      (atom [])
            permissions   (atom [])
            content-before (atom nil)
            cwd           (.cwd js/process)
            doc-path      (path/resolve "resources/gremllm-launch-log.md")]
        (-> (.readFile fsp doc-path "utf8")
            (.then (fn [content] (reset! content-before content)))
            (.then (fn [_] (acp/initialize (acp/make-session-update-callback store #(swap! captured conj %)) false #(swap! permissions conj %))))
            (.then (fn [_] (acp/new-session cwd)))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         "Read the linked document, then propose a single edit: Update the title to something arbitrary. Do not change anything else."
                         doc-path))))
            (.then (fn [^js result]
                     (is (= "end_turn" (.-stopReason result)))
                     (print-updates @captured)
                     (print-permissions @permissions)
                     (let [evts (updates captured)]
                       (assert-diffs-target evts doc-path)
                       (assert-tools-completed evts)
                       (assert-permission-contract @permissions)
                       (assert-permission-kinds @permissions))))
            (.then (fn [_] (.readFile fsp doc-path "utf8")))
            (.then (fn [content-after]
                     (is (= @content-before content-after)
                         "Document must be unchanged (writeTextFile is a no-op)")))
            (.catch (fn [err]
                      (is false (str "Document-first edit test failed: " err))))
            (.finally (fn []
                        (acp/shutdown)
                        (done))))))))
