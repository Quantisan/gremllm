(ns gremllm.main.effects.acp-integration-test
  (:require ["fs/promises" :as fsp]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.actions]
            [gremllm.main.actions.acp :as acp-actions]
            [gremllm.main.effects.acp :as acp]
            [gremllm.main.effects.acp-trace :as acp-trace]
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
  [{:keys [copy-doc?]}]
  (let [tmp-dir  (path/join (.tmpdir os) (str "gremllm-test-" (random-uuid)))
        src-path (path/resolve "resources/gremllm-launch-log.md")]
    {:store    (atom {})
     :recorder (acp-trace/make-recorder)
     :tmp-dir  tmp-dir
     :src-path src-path
     :doc-path (when copy-doc?
                 (path/join tmp-dir "gremllm-launch-log.md"))}))

(defn- initialize-recorded-acp!
  [{:keys [store recorder]}]
  (with-redefs [acp/read-text-file (make-read-recorder (:on-read recorder))]
    (acp/initialize
      {:on-session-update (acp/make-session-update-callback store (:on-session-update recorder))
       :on-permission     (:on-permission recorder)
       :on-write          (:on-write recorder)})))

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
                     (print-event-summary! "read-only" (:recorder ctx) [:read :write :permission])
                     (println "=== end ===")))
            (.catch (fn [err]
                      (is false (str "read-only test failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "read-only"
                                                      :prompt   "Summarize the first paragraph..."
                                                      :done     done}))))))))

(defn- some-diff-event?
  "True when the recorder captured at least one session update carrying diff content
   (i.e. an :edit-completed? event consumable by the diff-staging pipeline)."
  [events]
  (some (fn [{:keys [kind payload]}]
          (and (= :session-update kind)
               (acp-codec/edit-completed? (:update payload))))
        events))

(deftest test-live-document-first-edit
  (testing "document-first edit: trace all coerced events, observe (not assert) writeTextFile"
    (async done
      (let [ctx    (live-acp-context {:copy-doc? true})
            result (atom nil)]
        (-> (setup-live-acp! ctx)
            (.then (fn [_] (acp/new-session (:tmp-dir ctx))))
            (.then (fn [session-id]
                     (acp/prompt session-id
                       (acp-actions/prompt-content-blocks
                         {:text "Read the linked document. Do not plan or ask questions; just make one edit now: change the title to anything. Do not change anything else."}
                         (:doc-path ctx)))))
            (.then (fn [^js r]
                     (reset! result r)
                     (is (= "end_turn" (.-stopReason r)))
                     (is (some-diff-event? @(-> ctx :recorder :events))
                         "Expected at least one session update with diff content (edit-completed?). The agent must propose file mutations via a diff-emitting channel; until the follow-up to commit 322fc41 lands this assertion is the red guide-rail.")
                     (print-event-summary! "document-first-edit" (:recorder ctx) [:write])
                     (println "=== end ===")
                     ;; disallowedTools blocks Edit/Write/MultiEdit/NotebookEdit but not Bash;
                     ;; comparing on-disk content to the source copy also catches Bash-based circumvention.
                     (js/Promise.all #js [(.readFile fsp (:src-path ctx) "utf8")
                                          (.readFile fsp (:doc-path ctx) "utf8")])))
            (.then (fn [^js contents]
                     (is (= (aget contents 0) (aget contents 1))
                         "Expected document.md on disk to be unchanged from the source copy.")))
            (.catch (fn [err]
                      (is false (str "document-first-edit test failed: " err))))
            (.finally (fn []
                        (finish-live-acp! ctx result {:scenario "document-first-edit"
                                                      :prompt   "...change the title to anything..."
                                                      :done     done}))))))))
