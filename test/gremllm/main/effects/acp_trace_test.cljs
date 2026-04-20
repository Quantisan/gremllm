(ns gremllm.main.effects.acp-trace-test
  (:require ["fs/promises" :as fsp]
            ["os" :as os]
            ["path" :as path]
            [cljs.reader]
            [cljs.test :refer [deftest is testing async]]
            [gremllm.main.effects.acp-trace :as acp-trace]))

(deftest test-make-recorder-shape
  (testing "returns an events atom and four tap functions"
    (let [{:keys [events on-session-update on-permission on-write on-read]} (acp-trace/make-recorder)]
      (is (= cljs.core/Atom (type events)))
      (is (fn? on-session-update))
      (is (fn? on-permission))
      (is (fn? on-write))
      (is (fn? on-read)))))

(deftest test-events-accumulate-in-order
  (testing "all four taps append to the same atom in arrival order"
    (let [{:keys [events on-session-update on-permission on-write on-read]} (acp-trace/make-recorder)]
      (on-session-update {:session-update :agent-message-chunk :acp-session-id "s1"})
      (on-read {:path "/doc.md" :line nil :limit nil})
      (on-write {:path "/doc.md" :session-id "s1" :content-length 10})
      (on-permission {:acp-session-id "s1" :tool-call {:tool-call-id "tc1"}})
      (is (= 4 (count @events)))
      (is (= [:session-update :read :write :permission] (map :kind @events)))
      (is (every? number? (map :ts @events))))))

(deftest test-write-trace-creates-file
  (testing "write-trace! creates an EDN file in the given directory"
    (async done
      (let [dir (path/join (.tmpdir os) (str "acp-trace-test-" (random-uuid)))
            {:keys [events on-read]} (acp-trace/make-recorder)]
        (on-read {:path "/doc.md" :line nil :limit nil})
        (-> (acp-trace/write-trace! dir "test-scenario" {:versions {:gremllm "0.6.0"}} events)
            (.then (fn [file-path]
                     (is (string? file-path))
                     (is (re-find #"test-scenario" file-path))
                     (.readFile fsp file-path "utf8")))
            (.then (fn [raw]
                     (let [data (cljs.reader/read-string raw)]
                       (is (= "test-scenario" (:scenario data)))
                       (is (string? (:started-at data)))
                       (is (= 1 (count (:events data))))
                       (is (= :read (-> data :events first :kind))))))
            (.catch (fn [err]
                      (is false (str "write-trace! failed: " err))))
            (.finally (fn []
                        (-> (.rm fsp dir #js {:recursive true :force true})
                            (.finally done)))))))))
