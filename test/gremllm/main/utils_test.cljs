(ns gremllm.main.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [gremllm.main.utils :as utils]
            [nexus.registry :as nxr]))

(deftest test-nxr-result-integration
  (testing "nxr-result extracts value from real Nexus dispatch"
    ;; Register a simple test effect
    (nxr/register-effect! ::test-effect
      (fn [_ _ return-value]
        return-value))

    ;; Test with string value
    (let [dispatch-result (nxr/dispatch (atom {}) {} [[::test-effect "test-value"]])]
      (is (= "test-value" (utils/nxr-result dispatch-result))))

    ;; Test with map value
    (let [complex-value {:status 200 :body "response"}
          dispatch-result (nxr/dispatch (atom {}) {} [[::test-effect complex-value]])]
      (is (= complex-value (utils/nxr-result dispatch-result))))

    ;; Test with nil value
    (let [dispatch-result (nxr/dispatch (atom {}) {} [[::test-effect nil]])]
      (is (nil? (utils/nxr-result dispatch-result)))))

  (testing "nxr-result works with batched effects"
    ;; Register a batched effect
    (nxr/register-effect! ::test-batched-effect
      ^:nexus/batch
      (fn [_ _ values]
        ;; Return the first value for testing
        (ffirst values)))

    (let [dispatch-result (nxr/dispatch (atom {}) {}
                                       [[::test-batched-effect "first"]
                                        [::test-batched-effect "second"]])]
      (is (= "first" (utils/nxr-result dispatch-result)))))

  (testing "nxr-result handles effects that throw errors"
    (nxr/register-effect! ::test-error-effect
      (fn [_ _ _]
        (throw (js/Error. "Test error"))))

    (let [dispatch-result (nxr/dispatch (atom {}) {} [[::test-error-effect]])]
      ;; When effect throws, results should be empty
      (is (nil? (utils/nxr-result dispatch-result)))
      ;; But errors should be populated
      (is (seq (:errors dispatch-result))))))
