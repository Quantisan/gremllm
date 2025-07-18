(ns gremllm.main.integration-test
  (:require [gremllm.main.actions.secrets :as secrets]))

(defn test-safe-storage
  "Minimal integration test for Electron's safeStorage API.
   Runs on dev startup to ensure encryption is working."
  []
  ;; Core requirement: encryption must be available
  (assert (secrets/check-availability) "safeStorage encryption not available!")
  
  ;; Test basic encrypt/decrypt cycle
  (let [test-string "test-api-key-12345"
        encrypted (secrets/encrypt-value test-string)
        decrypted (secrets/decrypt-value encrypted)]
    (assert (string? encrypted) "encrypt-value should return string")
    (assert (= decrypted test-string) "Decrypt failed to match original"))
  
  ;; Test edge cases
  (assert (nil? (secrets/encrypt-value nil)) "encrypt-value should return nil for nil input")
  (assert (nil? (secrets/decrypt-value nil)) "decrypt-value should return nil for nil input")
  (assert (nil? (secrets/decrypt-value "invalid-base64")) "decrypt-value should return nil for invalid input")
  
  ;; Single success message
  (println "[safeStorage] ✓ Integration test passed"))
