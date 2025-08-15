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
