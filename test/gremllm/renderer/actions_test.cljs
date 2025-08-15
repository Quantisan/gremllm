(ns gremllm.renderer.actions-test
  (:require [cljs.test :refer [deftest is async]]
            [nexus.registry :as nxr]
            [gremllm.renderer.actions :as actions]))

(deftest test-promise->actions-success
  (async done
    (let [store (atom {})
          promise (js/Promise.resolve "success-value")]
      
      ;; Register a test effect to capture the result
      (nxr/register-effect! :test/capture
        (fn [_ store value]
          (swap! store assoc :result value)))
      
      ;; Dispatch using the registry
      (nxr/dispatch store {}
        [[:effects/promise 
          {:promise promise
           :on-success [:test/capture]}]])
      
      (js/setTimeout
        #(do (is (= "success-value" (:result @store)))
             (done))
        10))))

(deftest test-promise->actions-error
  (async done
    (let [store (atom {})
          promise (js/Promise.reject "error-value")]
      
      (nxr/register-effect! :test/capture-error
        (fn [_ store value]
          (swap! store assoc :error value)))
      
      (nxr/dispatch store {}
        [[:effects/promise 
          {:promise promise
           :on-error [:test/capture-error]}]])
      
      (js/setTimeout
        #(do (is (= "error-value" (:error @store)))
             (done))
        10))))
