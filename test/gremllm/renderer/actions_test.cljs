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
           :scope   :t1
           ;; Result of on-success is directed to save at :result of our data store
           :on-success [:state/->> [:nexus :promise :t1 :success] [:effects/save [:result]]]}]])

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
           :scope   :t2
           :on-error [:state/->> [:nexus :promise :t2 :error] [:effects/save [:error]]]}]])

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
          [[:effects/save [:level-2-called] true]  ; Track that level-2 effect was called
           [:effects/promise
            {:promise    (js/Promise.resolve "inner-result")
             :scope      (:scope opts)
             :on-success (:on-success opts)}]])))

    ;; Action that returns the above effect (mimics topic.actions/determine-initial-topic)
    (nxr/register-action! :test.actions/level-2
      (fn [_ topics-data]
        [[:effects/save [:level-2-action-called] topics-data]  ; Track level-2 action was called with correct data
         [:test.effects/level-2
          {:scope      :inner
           :on-success [:state/->> [:nexus :promise :inner :success] [:effects/save [:result]]]}]]))

    ;; Effect that dispatches promise with on-success pointing to action (mimics topic.effects/list)
    (nxr/register-effect! :test.effects/level-1
      (fn [{:keys [dispatch]} _ & [opts]]
        (dispatch
          [[:effects/save [:level-1-called] true]  ; Track that level-1 effect was called
           [:effects/promise
            {:promise    (js/Promise.resolve "outer-result")
             :scope      (:scope opts)
             :on-success (:on-success opts)}]])))

    ;; Action that starts the chain (mimics bootstrap)
    (nxr/register-action! :test.actions/bootstrap
      (fn [_ _]
        [[:effects/save [:bootstrap-called] true]  ; Track that bootstrap was called
         [:test.effects/level-1
          {:scope      :outer
           :on-success [:state/-> [:nexus :promise :outer :success] [:test.actions/level-2]]}]]))

    (let [store (atom {})]
      (nxr/dispatch store {} [[:test.actions/bootstrap]])
      (js/setTimeout
        #(do
          ;; Check that each level was called in sequence
          (is (= true (:bootstrap-called @store)) "Bootstrap action should be called")
          (is (= true (:level-1-called @store)) "Level-1 effect should be called")
          (is (= "outer-result" (:level-2-action-called @store)) "Level-2 action should receive outer-result")
          (is (= true (:level-2-called @store)) "Level-2 effect should be called")
          (is (= "inner-result" (:result @store)) "Final result should be inner-result")
          (done))
        20))))
