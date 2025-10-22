(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]
            [gremllm.schema :as schema]
            [malli.core :as m]))

;; TODO: too many non-critical tests, let's trim this down

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

(deftest test-system-info-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (let [ipc-data #js {:encryptionAvailable true
                        :secrets #js {:anthropic-api-key "sk-ant-1234"
                                      :openai-api-key "sk-proj-5678"}}
          result (schema/system-info-from-ipc ipc-data)]
      (is (= {:encryption-available true
              :secrets {:api-keys {:anthropic "sk-ant-1234"
                                   :openai "sk-proj-5678"}}}
             result))))

  (testing "converts camelCase to kebab-case"
    (let [ipc-data #js {:encryptionAvailable false
                        :secrets #js {}
                        :otherField "value"}
          result (schema/system-info-from-ipc ipc-data)]
      (is (= false (:encryption-available result)))
      (is (= "value" (:other-field result)))))

  (testing "handles missing secrets gracefully"
    (let [ipc-data #js {:encryptionAvailable true}
          result (schema/system-info-from-ipc ipc-data)]
      (is (= true (:encryption-available result))))))

(deftest test-has-api-key?
  (testing "returns true when provider has a key"
    (let [state {:system {:secrets {:api-keys {:anthropic "sk-ant-1234"
                                                :openai "sk-proj-5678"
                                                :google nil}}}}]
      (is (true? (system/has-api-key? state :anthropic)))
      (is (true? (system/has-api-key? state :openai)))))

  (testing "returns false when key is nil or missing"
    (let [state {:system {:secrets {:api-keys {:anthropic "sk-ant-1234"
                                                :openai nil}}}}]
      (is (false? (system/has-api-key? state :google)))
      (is (false? (system/has-api-key? state :openai)))))

  (testing "works for all supported providers"
    (let [state {:system {:secrets {:api-keys {:anthropic "sk-ant-1234"
                                                :openai "sk-proj-5678"
                                                :google "AIza9012"}}}}]
      (doseq [provider schema/supported-providers]
        (is (true? (system/has-api-key? state provider)))))))

