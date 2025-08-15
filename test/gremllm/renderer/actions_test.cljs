(ns gremllm.renderer.actions-test
  (:require [cljs.test :refer [deftest is async]]
            [nexus.registry :as nxr]
            [gremllm.renderer.actions]))


(deftest test-promise->actions-success
  (async done
    (let [store    (atom {})
          expected "success-value"
          promise  (js/Promise.resolve expected)]
      (nxr/dispatch store {}
        [[:effects/promise
          {:promise promise
           ;; Result of on-success is directed to save at :result of our data store
           :on-success [:effects/save [:result]]}]])

      (js/setTimeout
        #(do (is (= expected (:result @store)))
             (done))
        10))))

(deftest test-promise->actions-error
  (async done
    (let [store    (atom {})
          expected "error-value"
          promise  (js/Promise.reject expected)]
      (nxr/dispatch store {}
        [[:effects/promise
          {:promise promise
           :on-error [:effects/save [:error]]}]])

      (js/setTimeout
        #(do (is (= expected (:error @store)))
             (done))
        10))))
