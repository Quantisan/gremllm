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
        10))))

(deftest test-promise->actions-indirectly-nested-uses-inner-success
  (async done
    ;; Effect that dispatches promise (mimics topic.effects/load-latest-topic)
    (nxr/register-effect! :test.effects/level-2
      (fn [{:keys [dispatch]} _ & [opts]]
        (dispatch
          [[:effects/promise
            {:promise    (js/Promise.resolve "inner-result")
             :on-success (:on-success opts)}]])))

    ;; Action that returns the above effect (mimics topic.actions/determine-initial-topic)
    (nxr/register-action! :test.actions/level-2
      (fn [_ _topics-data]
        [[:test.effects/level-2
          {:on-success [:effects/save [:result] [:promise/success-value]]}]]))

    ;; Effect that dispatches promise with on-success pointing to action (mimics topic.effects/list)
    (nxr/register-effect! :test.effects/level-1
      (fn [{:keys [dispatch]} _ & [opts]]
        (dispatch
          [[:effects/promise
            {:promise    (js/Promise.resolve "outer-result")
             :on-success (:on-success opts)}]])))

    ;; Action that starts the chain (mimics bootstrap)
    (nxr/register-action! :test.actions/bootstrap
      (fn [_ _]
        [[:test.effects/level-1
          {:on-success [:test.actions/level-2 [:promise/success-value]]}]]))

    (let [store (atom {})]
      (nxr/dispatch store {} [[:test.actions/bootstrap]])
      (js/setTimeout
        #(do
          (is (= "inner-result" (:result @store)))
          (done))
        20))))
