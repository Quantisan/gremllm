(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]
            [gremllm.schema :as schema]))

(deftest test-secrets-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (is (= {:api-keys {:anthropic "sk-ant-1234"
                       :openai    "sk-proj-5678"
                       :google    "AIza9012"}}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :openai-api-key    "sk-proj-5678"
                                     :gemini-api-key    "AIza9012"})))))


(deftest test-system-info-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (let [ipc-data #js {:encryption-available? true
                        :secrets #js {:anthropic-api-key "sk-ant-1234"
                                      :openai-api-key "sk-proj-5678"}}
          result (schema/system-info-from-ipc ipc-data)]
      (is (= {:encryption-available? true
              :secrets {:api-keys {:anthropic "sk-ant-1234"
                                   :openai "sk-proj-5678"}}}
             result)))))

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

