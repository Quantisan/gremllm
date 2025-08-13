(ns gremllm.renderer.actions-test
  (:require [cljs.test :refer [deftest is async]]
            [gremllm.renderer.actions :as actions]))

(deftest test-promise->actions-success
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.resolve "success-value")]

      (actions/promise->actions ctx nil
        {:promise promise
         :on-success [:action/success]})

      ;; Give promise handlers time to execute
      (js/setTimeout
        #(do (is (= [[:action/success "success-value"]] @dispatched))
             (done))
        10))))

(deftest test-promise->actions-error
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.reject "error-value")]

      (actions/promise->actions ctx nil
        {:promise promise
         :on-error [:action/error]})

      ;; Give promise handlers time to execute
      (js/setTimeout
        #(do (is (= [[:action/error "error-value"]] @dispatched))
             (done))
        10))))

(deftest test-promise->actions-success-multiple
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch (fn
                           ([actions] (reset! dispatched {:actions actions}))
                           ([actions extra] (reset! dispatched {:actions actions :extra extra})))}
          promise (js/Promise.resolve "success-value")]
      (actions/promise->actions ctx nil
        {:promise promise
         :on-success [[:action/one]
                      [:action/two :arg]]})
      (js/setTimeout
        #(do (is (= [[:action/one]
                     [:action/two :arg]]
                   (:actions @dispatched)))
             (is (= {:effects.promise/value "success-value"}
                    (:extra @dispatched)))
             (done))
        10))))

(deftest test-promise->actions-error-multiple
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch (fn
                           ([actions] (reset! dispatched {:actions actions}))
                           ([actions extra] (reset! dispatched {:actions actions :extra extra})))}
          promise (js/Promise.reject "error-value")]
      (actions/promise->actions ctx nil
        {:promise promise
         :on-error [[:action/err-one]
                    [:action/err-two :arg]]})
      (js/setTimeout
        #(do (is (= [[:action/err-one]
                     [:action/err-two :arg]]
                   (:actions @dispatched)))
             (is (= {:effects.promise/error "error-value"}
                    (:extra @dispatched)))
             (done))
        10))))

(deftest test-promise->actions-error-nil-followup
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.reject "err")]
      (actions/promise->actions ctx nil {:promise promise :on-error nil})
      (js/setTimeout
        #(do (is (nil? @dispatched))
             (done))
        10))))

;; Concurrency contract:
;; - Two independent promises resolve out of order (B before A).
;; - Each dispatch carries only its own extra dispatch-data.
;; - We capture dispatch calls (both arities) and assert no cross-talk.
(deftest test-promise->actions-concurrent-out-of-order
  (async done
    (let [dispatches (atom [])]
      (letfn [
        ;; Capture dispatch exactly like Nexus would call it (1-arity and 2-arity)
              (record!
                ([actions] (swap! dispatches conj {:actions actions}))
                ([actions extra] (swap! dispatches conj {:actions actions :extra extra})))
        ;; Create a deferred promise whose resolve we can call later
              (make-deferred []
                (let [resolve* (atom nil)
                      p (js/Promise. (fn [resolve _] (reset! resolve* resolve)))]
                  {:promise p
                   :resolve #(when-let [f @resolve*] (f %))}))
        ;; Complete the test once we’ve observed two dispatches
              (finish-when-two []
                (add-watch dispatches ::done
                  (fn [_ _ _ new]
                    (when (= 2 (count new))
                      (remove-watch dispatches ::done)
                      ;; First dispatch corresponds to pB ("B")
                      (is (= [[:b/one [:effects.promise/value]]]
                             (:actions (first new))))
                      (is (= {:effects.promise/value "B"}
                             (:extra (first new))))
                      ;; Second dispatch corresponds to pA ("A")
                      (is (= [[:a/one [:effects.promise/value]]]
                             (:actions (second new))))
                      (is (= {:effects.promise/value "A"}
                             (:extra (second new))))
                      (done)))))]

        (let [ctx {:dispatch record!}
              pA (make-deferred)
              pB (make-deferred)]
          ;; Start watching before resolving to avoid races
          (finish-when-two)

          ;; Wire both promises through the effect. Note: placeholders are not
          ;; resolved here; we only assert the extra dispatch-data is correct.
          (actions/promise->actions ctx nil
            {:promise (:promise pA)
             :on-success [[:a/one [:effects.promise/value]]]})
          (actions/promise->actions ctx nil
            {:promise (:promise pB)
             :on-success [[:b/one [:effects.promise/value]]]})

          ;; Resolve B first, then A (out-of-order completion)
          ((:resolve pB) "B")
          ((:resolve pA) "A"))))))

(deftest normalize-followups-nil-test
  (is (nil? (actions/normalize-followups nil :p))))

(deftest normalize-followups-single-test
  (is (= [[:action/one :p]]
         (actions/normalize-followups [:action/one] :p))))

(deftest normalize-followups-multiple-vector-test
  (is (= [[:action/a]
          [:action/b :x]]
         (actions/normalize-followups [[:action/a]
                                       [:action/b :x]]
                                      :p))))

(deftest normalize-followups-multiple-list-test
  (is (= [[:action/a]
          [:action/b]]
         (actions/normalize-followups (list [:action/a]
                                            [:action/b])
                                      :p))))

(deftest normalize-followups-invalid-test
  (is (nil? (actions/normalize-followups "not-valid" :p))))

(deftest test-promise->actions-success-nil-followup
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.resolve "ok")]
      (actions/promise->actions ctx nil {:promise promise :on-success nil})
      (js/setTimeout
        #(do (is (nil? @dispatched))
             (done))
        10))))



