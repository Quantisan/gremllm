(ns gremllm.main.actions.secrets
  (:require ["electron" :refer [safeStorage]]))

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
