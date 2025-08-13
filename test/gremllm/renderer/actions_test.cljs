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

(deftest test-promise->actions-concurrent-out-of-order
  (async done
    (let [calls (atom [])
          ctx {:dispatch (fn
                           ([actions] (swap! calls conj {:actions actions}))
                           ([actions extra] (swap! calls conj {:actions actions :extra extra})))}
          res1 (atom nil)
          res2 (atom nil)
          p1 (js/Promise.
               (fn [resolve _reject]
                 (reset! res1 resolve)))
          p2 (js/Promise.
               (fn [resolve _reject]
                 (reset! res2 resolve)))]
      ;; Wire both through the promise effect
      (actions/promise->actions ctx nil
        {:promise p1
         :on-success [[:a/one [:effects.promise/value]]]})
      (actions/promise->actions ctx nil
        {:promise p2
         :on-success [[:b/one [:effects.promise/value]]]})
      ;; Resolve p2 first, then p1
      (js/setTimeout (fn [] ((deref res2) "B")) 5)
      (js/setTimeout (fn [] ((deref res1) "A")) 10)
      ;; Assert each dispatch carried the correct, isolated extra
      (js/setTimeout
        (fn []
          (is (= 2 (count @calls)))
          (is (= [[:b/one [:effects.promise/value]]]
                 (:actions (first @calls))))
          (is (= {:effects.promise/value "B"}
                 (:extra (first @calls))))
          (is (= [[:a/one [:effects.promise/value]]]
                 (:actions (second @calls))))
          (is (= {:effects.promise/value "A"}
                 (:extra (second @calls))))
          (done))
        30))))

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



