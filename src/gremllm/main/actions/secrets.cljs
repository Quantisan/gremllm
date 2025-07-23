(ns gremllm.main.actions.secrets
  (:require ["electron" :refer [safeStorage app]]
            [gremllm.main.io :as io]
            [clojure.walk :as walk]))

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

;; Helper function to get secrets file path
(defn- get-secrets-filepath []
  (-> (.getPath app "userData")
      (io/secrets-file-path)))

(defn- update-secrets-file
  "Update secrets file with the given function"
  [update-fn]
  (let [filepath (get-secrets-filepath)]
    (-> (io/read-secrets-file filepath)
        (update-fn)
        (->> (io/write-secrets-file filepath)))))

;; Effect handlers that combine encryption with file I/O

(defn save
  "Save an encrypted secret to the secrets file"
  [key value]
  (if-let [encrypted-value (encrypt-value value)]
    (do (update-secrets-file #(add-secret % key encrypted-value))
        {:ok true})
    {:error (if (check-availability)
              "Failed to encrypt value"
              "Encryption not available")}))

(defn load
  "Retrieve and decrypt a specific secret"
  [_ _ key]
  (if-let [decrypted (some-> (get-secrets-filepath)
                             (io/read-secrets-file)
                             (get key)
                             (decrypt-value))]
    {:ok decrypted}
    {:error "Secret not found or failed to decrypt"}))

(defn del
  "Delete a secret from the secrets file"
  [key]
  (update-secrets-file #(remove-secret % key))
  {:ok true})

;; TODO: move some of these fns to effects
(defn load-all
  "Load and decrypt all secrets from the secrets file"
  []
  (let [filepath (get-secrets-filepath)
        encrypted-secrets (io/read-secrets-file filepath)]
    (reduce-kv (fn [acc k encrypted-v]
                 (if-let [decrypted (decrypt-value encrypted-v)]
                   (assoc acc k decrypted)
                   acc))
               {}
               encrypted-secrets)))

(defn redact-secret-value
  "Returns a redacted suffix of the secret value based on its length.
  nil -> nil
  < 12 chars -> empty string
  12-19 chars -> last 2 chars
  20+ chars -> last 4 chars"
  [value]
  (when-let [s (some-> value str)]
    (let [n (count s)]
      (cond
        (< n 12) ""                ; Too short, show nothing
        (< n 20) (subs s (- n 2))  ; Medium length, show last 2
        :else (subs s (- n 4)))))) ; Long enough, show last 4

(defn redact-all-string-values
  "Recursively redacts all string values in a nested data structure.
  Preserves structure and keys, only redacts leaf string values."
  [data]
  (walk/prewalk
    (fn [x]
      (if (map-entry? x)
        (let [[k v] x]
          [k (if (string? v) (redact-secret-value v) v)])
        x))
    data))
