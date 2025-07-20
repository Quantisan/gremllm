(ns gremllm.main.actions.secrets
  (:require ["electron" :refer [safeStorage app]]
            [gremllm.main.io :as io]))

;; Reference to Electron's safeStorage API
(def safe-storage safeStorage)

;; Pure functions for managing the secrets data structure
(defn create-empty-secrets []
  {})

(defn add-secret [secrets-map key value]
  (assoc secrets-map key value))

(defn remove-secret [secrets-map key]
  (dissoc secrets-map key))

;; Encryption availability check
(defn check-availability []
  (.isEncryptionAvailable safe-storage))

;; Encrypt a value to base64 string
(defn encrypt-value [value]
  (when (and (check-availability) value)
    (try
      (-> (.encryptString safe-storage value)
          (.toString "base64"))
      (catch :default _
        nil))))

;; Decrypt a base64 string back to original value
(defn decrypt-value [encrypted-base64]
  (when encrypted-base64
    (try
      (let [buffer (js/Buffer.from encrypted-base64 "base64")]
        (.decryptString safe-storage buffer))
      (catch :default _
        nil))))

;; Effect handlers that combine encryption with file I/O

(defn save
  "Save an encrypted secret to the secrets file"
  [_ _ key value]
  (if (check-availability)
    (let [user-data-dir (.getPath app "userData")
          filepath (io/secrets-file-path user-data-dir)
          current-secrets (io/read-secrets-file filepath)
          encrypted-value (encrypt-value value)]
      (if encrypted-value
        (do
          (io/write-secrets-file filepath (add-secret current-secrets key encrypted-value))
          {:ok true})
        {:error "Failed to encrypt value"}))
    {:error "Encryption not available"}))

(defn load
  "Retrieve and decrypt a specific secret"
  [_ _ key]
  (let [user-data-dir (.getPath app "userData")
        filepath (io/secrets-file-path user-data-dir)
        secrets (io/read-secrets-file filepath)
        encrypted-value (get secrets key)]
    (if encrypted-value
      (if-let [decrypted (decrypt-value encrypted-value)]
        {:ok decrypted}
        {:error "Failed to decrypt value"})
      {:error "Secret not found"})))

(defn del
  "Delete a secret from the secrets file"
  [_ _ key]
  (let [user-data-dir (.getPath app "userData")
        filepath (io/secrets-file-path user-data-dir)
        current-secrets (io/read-secrets-file filepath)
        updated-secrets (remove-secret current-secrets key)]
    (io/write-secrets-file filepath updated-secrets)
    {:ok true}))

(defn list-keys
  "Get all secret keys (not values)"
  [_ _]
  (let [user-data-dir (.getPath app "userData")
        filepath (io/secrets-file-path user-data-dir)
        secrets (io/read-secrets-file filepath)]
    {:ok (keys secrets)}))
