(ns gremllm.renderer.state.system-test
  (:require [cljs.test :refer [deftest testing is]]
            [gremllm.renderer.state.system :as system]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-secrets
  ([]
   (create-secrets {}))
  ([{:keys [anthropic openai google]}]
   (assoc (m/decode schema/NestedSecrets {} mt/default-value-transformer)
          :api-keys (into {}
                          (keep (fn [[k v]] (when v [k v]))
                                {:anthropic anthropic
                                 :openai openai
                                 :google google})))))

(deftest test-secrets-from-ipc
  (testing "transforms flat IPC secrets to nested structure"
    (is (= (create-secrets {:anthropic "sk-ant-1234"
                            :openai    "sk-proj-5678"
                            :google    "AIza9012"})
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
              :secrets (create-secrets {:anthropic "sk-ant-1234"
                                        :openai "sk-proj-5678"})}
             result)))))

(deftest test-has-any-api-key?
  (testing "returns true when at least one provider has a key"
    (let [state {:system {:secrets (create-secrets {:anthropic "sk-ant-1234"
                                                    :openai nil})}}]
      (is (true? (system/has-any-api-key? state)))))

  (testing "returns false when no providers have keys"
    (let [state {:system {:secrets (create-secrets {:anthropic nil
                                                    :openai nil})}}]
      (is (false? (system/has-any-api-key? state))))))

