(ns gremllm.main.actions.secrets-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.secrets :as secrets]))

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

(deftest test-encrypt-when-unavailable
  (testing "returns nil when encryption not available"
    (with-redefs [secrets/check-availability (fn [] false)]
      (is (nil? (secrets/encrypt-value "secret-value"))))))

(deftest test-decrypt-invalid-input
  (testing "decrypt-value returns nil for invalid inputs"
    (is (nil? (secrets/decrypt-value nil)))
    (is (nil? (secrets/decrypt-value "")))))

(deftest test-redact-secret-value
  (testing "redaction based on length"
    (is (nil? (secrets/redact-secret-value nil)))
    (is (= "" (secrets/redact-secret-value "")))
    (is (= "" (secrets/redact-secret-value "short")))       ; < 12 chars
    (is (= "" (secrets/redact-secret-value "still-short"))) ; 11 chars
    (is (= "12" (secrets/redact-secret-value "123456789012")))     ; 12 chars, show last 2
    (is (= "7890" (secrets/redact-secret-value "12345678901234567890"))) ; 20 chars, show last 4
    (is (= "6789" (secrets/redact-secret-value "123456789012345678901236789"))))) ; > 20 chars, show last 4

(deftest test-redact-all-values
  (testing "redacts all values recursively"
    (is (= {:api-key "6789"
            :nested {:secret ""
                     :token "34"}}
          (secrets/redact-all-values
            {:api-key "sk-123456789"
             :nested {:secret "short"
                      :token "abcdefghij34"}}))))
  
  (testing "preserves non-string values"
    (is (= {:count 42
            :active true
            :empty nil}
          (secrets/redact-all-values
            {:count 42
             :active true
             :empty nil})))))
