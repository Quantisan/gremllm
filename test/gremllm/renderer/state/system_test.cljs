(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(deftest test-secrets-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (is (= {:api-keys {:anthropic "sk-ant-1234"
                       :openai    "sk-proj-5678"
                       :google    "AIza9012"}}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :openai-api-key    "sk-proj-5678"
                                     :gemini-api-key    "AIza9012"}))))

  (testing "output validates against NestedSecrets schema"
    (let [result (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"})]
      (is (m/validate schema/NestedSecrets result)))))

(deftest test-has-anthropic-api-key?
  (testing "returns true when anthropic key exists"
    (is (true? (system/has-anthropic-api-key?
                {:system {:secrets {:api-keys {:anthropic "sk-ant-1234"}}}}))))

  (testing "returns false when key is nil or missing"
    (is (false? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {}}}})))
    (is (false? (system/has-anthropic-api-key?
                 {:system {:secrets {:api-keys {:anthropic nil}}}})))))

