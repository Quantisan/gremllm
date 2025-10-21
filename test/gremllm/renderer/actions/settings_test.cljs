(ns gremllm.renderer.actions.settings-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.settings :as settings]
            [gremllm.renderer.state.sensitive :as sensitive-state]
            [gremllm.schema :as schema]))

(deftest update-input-test
  (testing "returns save effect with correct path for each provider"
    (doseq [provider [:anthropic :openai :google]]
      (let [effects (settings/update-input {} provider "test-key-value")]
        (is (= 1 (count effects)))
        (is (= :effects/save (ffirst effects)))
        (is (= (conj sensitive-state/api-key-inputs-path provider)
               (second (first effects))))
        (is (= "test-key-value" (nth (first effects) 2)))))))

(deftest key-name-derivation-test
  (testing "derives correct storage key name for each provider"
    (is (= "anthropic-api-key"
           (-> (schema/provider->api-key-keyword :anthropic) name)))
    (is (= "openai-api-key"
           (-> (schema/provider->api-key-keyword :openai) name)))
    (is (= "gemini-api-key"
           (-> (schema/provider->api-key-keyword :google) name)))))

(deftest save-key-test
  (testing "returns empty effects when input is empty"
    (doseq [provider [:anthropic :openai :google]]
      (let [state   {:sensitive {:api-key-inputs {provider ""}}}
            effects (settings/save-key state provider)]
        (is (= [] effects))))))

;; Note: save-key and remove-key create browser promises (window.electronAPI) which
;; aren't available in Node test environment. Promise creation is tested via integration
;; tests. Unit tests focus on key derivation (tested above) and handler behavior (tested below).

(deftest save-success-test
  (testing "clears input for provider and requests system info reload"
    (doseq [provider [:anthropic :openai :google]]
      (let [effects (settings/save-success {} nil provider)]
        (is (= 3 (count effects)))
        (is (= :effects/save (ffirst effects)))
        (is (= (conj sensitive-state/api-key-inputs-path provider)
               (second (first effects))))
        (is (= "" (nth (first effects) 2)))
        (is (= :system.actions/request-info (first (second effects))))
        (is (= :ui.actions/hide-settings (first (nth effects 2))))))))

(deftest remove-success-test
  (testing "requests system info reload and hides settings"
    (let [effects (settings/remove-success {} nil :anthropic)]
      (is (= 2 (count effects)))
      (is (= :system.actions/request-info (ffirst effects)))
      (is (= :ui.actions/hide-settings (first (second effects)))))))
