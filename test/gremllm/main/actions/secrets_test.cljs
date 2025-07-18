(ns gremllm.main.actions.secrets-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.secrets :as secrets]))

;; Mock Electron's safeStorage for testing
(defn mock-safe-storage
  [{:keys [available? encrypt-fn decrypt-fn]}]
  (clj->js {:isEncryptionAvailable (fn [] available?)
            :encryptString encrypt-fn
            :decryptString decrypt-fn}))

(deftest test-secrets-data-structure
  (testing "can create empty secrets structure"
    (is (= {} (secrets/create-empty-secrets))))

  (testing "can add encrypted value to secrets"
    (let [secrets-map (secrets/add-secret {} :api-key "encrypted-base64-value")]
      (is (= {:api-key "encrypted-base64-value"} secrets-map))))

  (testing "can remove secret from secrets"
    (let [secrets-map {:api-key "encrypted-value" :other-key "other-value"}
          updated (secrets/remove-secret secrets-map :api-key)]
      (is (= {:other-key "other-value"} updated)))))

(deftest test-check-availability
  (testing "returns true when encryption is available"
    (let [mock (mock-safe-storage {:available? true})]
      (with-redefs [secrets/safe-storage mock]
        (is (= true (secrets/check-availability))))))

  (testing "returns false when encryption is not available"
    (let [mock (mock-safe-storage {:available? false})]
      (with-redefs [secrets/safe-storage mock]
        (is (= false (secrets/check-availability)))))))

(deftest test-encrypt-value
  (testing "returns nil when encryption not available"
    (with-redefs [secrets/check-availability (fn [] false)]
      (is (nil? (secrets/encrypt-value "secret-value")))))

  (testing "returns nil when given nil value"
    (with-redefs [secrets/check-availability (fn [] true)]
      (is (nil? (secrets/encrypt-value nil)))))

  (testing "returns base64 string when encryption available"
    (let [mock-buffer (clj->js {:toString (fn [encoding]
                                            (when (= encoding "base64")
                                              "bW9jay1lbmNyeXB0ZWQ="))})
          mock (mock-safe-storage {:available? true
                                   :encrypt-fn (fn [_] mock-buffer)})]
      (with-redefs [secrets/check-availability (fn [] true)
                    secrets/safe-storage mock]
        (is (= "bW9jay1lbmNyeXB0ZWQ=" (secrets/encrypt-value "secret-value")))))))

(deftest test-decrypt-value
  (testing "returns nil when given nil"
    (is (nil? (secrets/decrypt-value nil))))

  (testing "returns nil on decryption error"
    (let [mock (mock-safe-storage {:decrypt-fn (fn [_]
                                                (throw (js/Error. "Decryption failed")))})]
      (with-redefs [secrets/safe-storage mock]
        (is (nil? (secrets/decrypt-value "bW9jay1lbmNyeXB0ZWQ="))))))

  (testing "returns decrypted string on success"
    (let [mock (mock-safe-storage {:decrypt-fn (fn [_] "decrypted-value")})]
      (with-redefs [secrets/safe-storage mock]
        (is (= "decrypted-value" (secrets/decrypt-value "bW9jay1lbmNyeXB0ZWQ=")))))))
