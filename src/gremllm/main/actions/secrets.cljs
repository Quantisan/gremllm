(ns gremllm.main.actions.secrets)

;; Pure functions for managing the secrets data structure
(defn create-empty-secrets []
  {})

(defn add-secret [secrets-map key value]
  (assoc secrets-map key value))

(defn remove-secret [secrets-map key]
  (dissoc secrets-map key))
