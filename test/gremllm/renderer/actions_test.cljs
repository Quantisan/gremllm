(ns gremllm.renderer.actions-test
  (:require [cljs.test :refer [deftest is async]]
            [nexus.registry :as nxr]
            [gremllm.renderer.actions]))


(deftest test-promise->actions-success
  (async done
    (let [store (atom {})
          promise (js/Promise.resolve "success-value")]
      (nxr/dispatch store {}
        [[:effects/promise
          {:promise promise
           :on-success [:effects/save [:result]]}]])

      (js/setTimeout
        #(do (is (= "success-value" (:result @store)))
             (done))
        10))))

(deftest test-promise->actions-error
  (async done
    (let [store (atom {})
          promise (js/Promise.reject "error-value")]
      (nxr/dispatch store {}
        [[:effects/promise
          {:promise promise
           :on-error [:effects/save [:error]]}]])

      (js/setTimeout
        #(do (is (= "error-value" (:error @store)))
             (done))
        10))))
