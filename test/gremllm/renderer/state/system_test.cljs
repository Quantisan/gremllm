(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]
            [gremllm.renderer.state.sensitive :as sensitive]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(deftest test-secrets-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (is (= {:api-keys {:anthropic "sk-ant-1234"}}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"}))))

  (testing "handles all three providers"
    (is (= {:api-keys {:anthropic "sk-ant-1234"
                       :openai    "sk-proj-5678"
                       :google    "AIza9012"}}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :openai-api-key    "sk-proj-5678"
                                     :gemini-api-key    "AIza9012"}))))

  (testing "preserves non-API-key entries"
    (is (= {:api-keys   {:anthropic "sk-ant-1234"}
            :other-key  "value"
            :more-stuff 42}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :other-key         "value"
                                     :more-stuff        42}))))

  (testing "handles missing keys"
    (is (= {:api-keys {}}
           (schema/secrets-from-ipc {})))
    (is (= {:api-keys   {:anthropic "sk-ant-1234"}
            :other-key  "value"}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :other-key         "value"}))))

  (testing "handles nil values"
    (is (= {:api-keys {:anthropic nil}}
           (schema/secrets-from-ipc {:anthropic-api-key nil})))
    (is (= {:api-keys {:anthropic "sk-ant-1234"
                       :openai    nil}}
           (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                     :openai-api-key    nil}))))

  (testing "output validates against NestedSecrets schema"
    (let [result (schema/secrets-from-ipc {:anthropic-api-key "sk-ant-1234"
                                           :openai-api-key    "sk-proj-5678"})]
      (is (m/validate schema/NestedSecrets result)))))

(deftest test-get-redacted-api-key
  (testing "retrieves redacted key for specific provider"
    (let [state {:system {:secrets {:api-keys {:anthropic "sk-ant-..."
                                                :openai    "sk-proj-..."
                                                :google    "AIza..."}}}}]
      (is (= "sk-ant-..." (system/get-redacted-api-key state :anthropic)))
      (is (= "sk-proj-..." (system/get-redacted-api-key state :openai)))
      (is (= "AIza..." (system/get-redacted-api-key state :google)))))

  (testing "returns nil when provider key missing"
    (let [state {:system {:secrets {:api-keys {:anthropic "sk-ant-..."}}}}]
      (is (nil? (system/get-redacted-api-key state :openai)))
      (is (nil? (system/get-redacted-api-key state :google)))))

  (testing "handles missing secrets structure"
    (is (nil? (system/get-redacted-api-key {} :anthropic)))
    (is (nil? (system/get-redacted-api-key {:system {}} :anthropic)))
    (is (nil? (system/get-redacted-api-key {:system {:secrets {}}} :anthropic))))

  (testing "handles empty api-keys map"
    (is (nil? (system/get-redacted-api-key {:system {:secrets {:api-keys {}}}} :anthropic)))))

(deftest test-has-anthropic-api-key?
  (testing "returns false when no anthropic key exists in nested structure"
    (is (false? (system/has-anthropic-api-key? {})))
    (is (false? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {}}}})))
    (is (false? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {:openai "sk-proj-..."}}}}))))

  (testing "returns true when anthropic key exists in nested structure"
    (is (true? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {:anthropic "sk-ant-1234"}}}})))
    (is (true? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {:anthropic ""}}}}))))

  (testing "returns false for nil value"
    (is (false? (system/has-anthropic-api-key? {:system {:secrets {:api-keys {:anthropic nil}}}})))))

(deftest test-get-api-key-input
  (testing "retrieves input for specific provider"
    (let [state {:sensitive {:api-key-inputs {:anthropic "sk-ant-new123"
                                               :openai    "sk-proj-new456"
                                               :google    "AIza-new789"}}}]
      (is (= "sk-ant-new123" (sensitive/get-api-key-input state :anthropic)))
      (is (= "sk-proj-new456" (sensitive/get-api-key-input state :openai)))
      (is (= "AIza-new789" (sensitive/get-api-key-input state :google)))))

  (testing "returns empty string when provider input missing"
    (let [state {:sensitive {:api-key-inputs {:anthropic "sk-ant-new123"}}}]
      (is (= "" (sensitive/get-api-key-input state :openai)))
      (is (= "" (sensitive/get-api-key-input state :google)))))

  (testing "returns empty string when no inputs exist"
    (is (= "" (sensitive/get-api-key-input {} :anthropic)))
    (is (= "" (sensitive/get-api-key-input {:sensitive {}} :anthropic)))
    (is (= "" (sensitive/get-api-key-input {:sensitive {:api-key-inputs {}}} :anthropic)))))
