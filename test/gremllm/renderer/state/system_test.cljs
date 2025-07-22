(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]))

(deftest test-has-anthropic-api-key?
  (testing "returns false when no anthropic key exists"
    (is (false? (system/has-anthropic-api-key? {})))
    (is (false? (system/has-anthropic-api-key? {:system {:secrets {:other-key "value"}}}))))

  (testing "returns true when anthropic-api-key exists"
    (is (true? (system/has-anthropic-api-key? {:system {:secrets {:anthropic-api-key "1234"}}})))
    (is (true? (system/has-anthropic-api-key? {:system {:secrets {:anthropic-api-key ""}}})))))
