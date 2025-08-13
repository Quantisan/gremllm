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
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.resolve "success-value")]
      (actions/promise->actions ctx nil
        {:promise promise
         :on-success [[:action/one]
                      [:action/two :arg]]})
      (js/setTimeout
        #(do (is (= [[:action/one "success-value"]
                     [:action/two :arg "success-value"]]
                   @dispatched))
             (done))
        10))))

(deftest test-promise->actions-error-multiple
  (async done
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.reject "error-value")]
      (actions/promise->actions ctx nil
        {:promise promise
         :on-error [[:action/err-one]
                    [:action/err-two :arg]]})
      (js/setTimeout
        #(do (is (= [[:action/err-one "error-value"]
                     [:action/err-two :arg "error-value"]]
                   @dispatched))
             (done))
        10))))

(deftest normalize-followups-nil-test
  (is (nil? (actions/normalize-followups nil :p))))

(deftest normalize-followups-single-test
  (is (= [[:action/one :p]]
         (actions/normalize-followups [:action/one] :p))))

(deftest normalize-followups-multiple-vector-test
  (is (= [[:action/a :p]
          [:action/b :x :p]]
         (actions/normalize-followups [[:action/a]
                                       [:action/b :x]]
                                      :p))))

(deftest normalize-followups-multiple-list-test
  (is (= [[:action/a :p]
          [:action/b :p]]
         (actions/normalize-followups (list [:action/a]
                                            [:action/b])
                                      :p))))

(deftest normalize-followups-invalid-test
  (is (nil? (actions/normalize-followups "not-valid" :p))))
