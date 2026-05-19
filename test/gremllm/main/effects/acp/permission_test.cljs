(ns gremllm.main.effects.acp.permission-test
  (:require [cljs.test :refer [deftest is testing async use-fixtures]]
            [gremllm.main.effects.acp.permission :as permission]
            [gremllm.schema.codec.acp :as acp-codec]))

(use-fixtures :each
  {:before #(permission/clear!)
   :after  #(permission/clear!)})

;; ---------------------------------------------------------------------------
;; Shared fixture helpers
;; ---------------------------------------------------------------------------

(defn- make-deferred-params
  "Build raw-params JS object whose policy will be :deferred (in-workspace edit).
   Uses a file path inside resources/ so the within-root? check passes."
  []
  (let [path-mod (js/require "path")
        cwd      (.resolve path-mod (.cwd js/process) "resources")
        file     (.resolve path-mod cwd "gremllm-launch-log.md")]
    {:raw-params #js {:sessionId "s-1"
                      :toolCall  #js {:toolCallId "tc-defer"
                                      :kind       "edit"
                                      :title      "Edit"
                                      :rawInput   #js {:file_path file}
                                      :content    #js [#js {:type    "diff"
                                                            :path    file
                                                            :oldText "a"
                                                            :newText "b"}]
                                      :locations  #js []}
                      :options   #js [#js {:optionId "allow-once"
                                           :kind     "allow_once"
                                           :name     "Allow"}
                                      #js {:optionId "reject-once"
                                           :kind     "reject_once"
                                           :name     "Reject"}]}
     :cwd cwd}))

;; ---------------------------------------------------------------------------
;; Deferred resolution: callback returns Promise, resolved by record-decision!
;; ---------------------------------------------------------------------------

(deftest test-resolve-cb-returns-promise-for-deferred
  (testing "in-workspace edit: callback returns a Promise that resolves when record-decision! fires"
    (async done
      (let [pending-events (atom [])
            {:keys [raw-params cwd]} (make-deferred-params)
            resolve-cb (permission/make-resolve-permission
                         {:on-permission-request     (fn [_] nil)
                          :on-awaiting-user-decision (fn [enriched]
                                                       (swap! pending-events conj enriched))})]
        (let [result-promise (resolve-cb raw-params cwd)]
          (is (instance? js/Promise result-promise))
          (is (= 1 (count @pending-events))
              "on-awaiting-user-decision tap should fire once for deferred request")
          (is (= "tc-defer" (-> @pending-events first :tool-call :tool-call-id)))
          (permission/record-decision! "tc-defer" "allow-once")
          (-> result-promise
              (.then (fn [^js js-out]
                       (is (= "selected"    (.. js-out -outcome -outcome)))
                       (is (= "allow-once"  (.. ^js js-out -outcome -optionId)))))
              (.finally (fn [] (done)))))))))

;; ---------------------------------------------------------------------------
;; Registry: record-decision! fires resolver, removes from snapshot
;; ---------------------------------------------------------------------------

(deftest test-pending-permission-registry
  (testing "record-decision! fires resolver and removes it from snapshot"
    (async done
      (let [{:keys [raw-params cwd]} (make-deferred-params)
            resolve-cb (permission/make-resolve-permission
                         {:on-awaiting-user-decision (fn [_] nil)})]
        (let [p (resolve-cb raw-params cwd)]
          (is (contains? (permission/awaiting-snapshot) "tc-defer"))
          (permission/record-decision! "tc-defer" "allow-once")
          (-> p
              (.then (fn [_]
                       (is (nil? (get (permission/awaiting-snapshot) "tc-defer"))
                           "resolver should be removed after firing")))
              (.finally (fn [] (done))))))))

  (testing "record-decision! with unknown id is a no-op"
    (is (nil? (permission/record-decision! "tc-unknown" "allow_once"))))

  (testing "clear! empties awaiting-user-decision registry"
    (async done
      (let [{:keys [raw-params cwd]} (make-deferred-params)
            resolve-cb (permission/make-resolve-permission {})]
        (resolve-cb raw-params cwd)
        (is (contains? (permission/awaiting-snapshot) "tc-defer"))
        (permission/clear!)
        (is (= {} (permission/awaiting-snapshot)))
        (done))))

  (testing "second deferred request for same tool-call-id replaces resolver"
    (async done
      (let [{:keys [raw-params cwd]} (make-deferred-params)
            resolve-cb (permission/make-resolve-permission {})
            _p1 (resolve-cb raw-params cwd) ; Promise intentionally never resolves

            p2  (resolve-cb raw-params cwd)]
        (permission/record-decision! "tc-defer" "reject-once")
        (-> p2
            (.then (fn [^js js-out]
                     (is (= "selected" (.. js-out -outcome -outcome)))))
            (.finally (fn [] (done))))))))

;; ---------------------------------------------------------------------------
;; Tap failure isolation: throwing tap must not replace resolved outcome
;; ---------------------------------------------------------------------------

(deftest test-permission-tap-failure-does-not-replace-outcome
  (testing "on-permission-request tap throwing does not replace the resolved outcome with cancelled"
    (let [path-mod   (js/require "path")
          cwd        (.resolve path-mod (.cwd js/process) "resources")
          raw-params #js {:sessionId "s-1"
                          :toolCall  #js {:toolCallId "tc-1"
                                          :kind       "read"
                                          :title      "Read file"
                                          :rawInput   #js {}
                                          :locations  #js []}
                          :options   #js [#js {:optionId "opt-1"
                                               :name     "Allow"
                                               :kind     "allow_once"}]}
          resolve-cb (permission/make-resolve-permission
                       {:on-permission-request (fn [_] (throw (js/Error. "tap-fail")))})
          ^js js-out (resolve-cb raw-params cwd)]
      (is (= "selected" (.. js-out -outcome -outcome))
          "tap failure must not replace outcome with cancelled"))))
