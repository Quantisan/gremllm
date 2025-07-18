(ns gremllm.main.integration-test
  (:require [gremllm.main.actions.secrets :as secrets]))

(defn test-safe-storage
  "Manual test function for Electron's safeStorage API.
   Call this from REPL or set debug-test-on-startup to true."
  []
  (println "\n=== Testing Electron safeStorage ===")

  ;; Test 1: Check availability
  (let [available? (.isEncryptionAvailable secrets/safe-storage)]
    (println "1. Encryption available?" available?)
    (println "   Platform:" (.-platform js/process))

    ;; Test 2: Get storage backend (Linux only)
    (when (= (.-platform js/process) "linux")
      (try
        (println "   Storage backend:" (.getSelectedStorageBackend secrets/safe-storage))
        (catch :default e
          (println "   Storage backend error:" (.-message e)))))

    ;; Only proceed with encryption tests if available
    (if available?
      (do
        ;; Test 3: Encrypt a string
        (println "\n2. Testing encryption...")
        (let [test-string "my-secret-api-key-12345"
              encrypted (.encryptString secrets/safe-storage test-string)
              base64-encrypted (.toString encrypted "base64")]
          (println "   Original:" test-string)
          (println "   Encrypted (base64):" base64-encrypted)
          (println "   Encrypted buffer length:" (.-length encrypted))

          ;; Test 4: Decrypt the string
          (println "\n3. Testing decryption...")
          (let [buffer-from-base64 (js/Buffer.from base64-encrypted "base64")
                decrypted (.decryptString secrets/safe-storage buffer-from-base64)]
            (println "   Decrypted:" decrypted)
            (println "   Matches original?" (= decrypted test-string)))

          ;; Test 5: Test our wrapper functions
          (println "\n4. Testing our wrapper functions...")
          (let [encrypted-wrapper (secrets/encrypt-value test-string)
                decrypted-wrapper (secrets/decrypt-value encrypted-wrapper)]
            (println "   Encrypted via wrapper:" encrypted-wrapper)
            (println "   Decrypted via wrapper:" decrypted-wrapper)
            (println "   Wrapper functions work?" (= decrypted-wrapper test-string)))

          ;; Test 6: Edge cases
          (println "\n5. Testing edge cases...")
          (println "   encrypt-value with nil:" (secrets/encrypt-value nil))
          (println "   decrypt-value with nil:" (secrets/decrypt-value nil))
          (println "   decrypt-value with invalid base64:"
                   (secrets/decrypt-value "invalid-base64"))))

      ;; When encryption not available
      (do
        (println "\n⚠️  Encryption not available on this system!")
        (println "   Testing fail-safe behavior...")
        (println "   encrypt-value returns nil?" (nil? (secrets/encrypt-value "test")))
        (println "   check-availability returns false?" (false? (secrets/check-availability))))))

  (println "\n=== safeStorage tests complete ===\n"))
