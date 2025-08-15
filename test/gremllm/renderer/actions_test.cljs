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
           :on-success [:effects/save [:result] [:promise/success-value]]}]])

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
           :on-error [:effects/save [:error] [:promise/error-value]]}]])

      (js/setTimeout
        #(do (is (= expected (:error @store)))
             (done))
        10)))

 (deftest test-promise->actions-indirectly-nested-uses-inner-success
   (async done
     ;; Effect that dispatches a new promise (mimics topic.effects/*)
     (nxr/register-effect! :test.effects/level-2
       (fn [{:keys [dispatch]} _ & [opts]]
         (dispatch
           [[:effects/promise
             {:promise    (js/Promise.resolve "inner")
              :on-success (:on-success opts)}]])))

     ;; Action that returns the above effect (mimics topic.actions/* -> topic.effects/*)
     (nxr/register-action! :test.actions/chain-level-1
       (fn [_ _]
         ;; v is the outer success; inner promise's on-success should not see it
         [[:test.effects/level-2
           {:on-success [:effects/save [:result] [:promise/success-value]]}]]))

    (let [store (atom {})]
      (nxr/dispatch store {}
         [[:effects/promise]
          {:promise (js/Promise.resolve "outer")
            :on-success [:test.actions/chain-level-1 [:promise/success-value]]}])
      (js/setTimeout
        #(do
          (is (= "inner" (:result @store)))
          (done))
        10)))))
