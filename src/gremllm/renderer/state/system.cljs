(ns gremllm.renderer.state.system
  (:require [gremllm.schema :as schema]))

(def system-info-path [:system])
(def redacted-anthropic-api-key-path (conj system-info-path :secrets :api-keys :anthropic))

(defn transform-secrets-for-state
  "Converts flat IPC secrets map to nested api-keys structure.
   {:anthropic-api-key 'sk-ant-xyz' :other-key 'val'}
   → {:api-keys {:anthropic 'sk-ant-xyz'} :other-key 'val'}

   Explicitly handles three provider keys for type safety and clarity."
  [secrets-map]
  (let [;; Build nested api-keys map from known provider keys
        api-keys (cond-> {}
                   (contains? secrets-map :anthropic-api-key)
                   (assoc :anthropic (:anthropic-api-key secrets-map))

                   (contains? secrets-map :openai-api-key)
                   (assoc :openai (:openai-api-key secrets-map))

                   (contains? secrets-map :gemini-api-key)
                   (assoc :google (:gemini-api-key secrets-map)))]
    (-> secrets-map
        ;; Remove flat keys
        (dissoc :anthropic-api-key :openai-api-key :gemini-api-key)
        ;; Add nested structure
        (assoc :api-keys api-keys))))

(defn encryption-available? [state]
  (get-in state (conj system-info-path :encryption-available?) false))

(defn get-redacted-api-key
  "Retrieves redacted API key for specified provider from state."
  [state provider]
  (get-in state (conj system-info-path :secrets :api-keys provider) nil))

(defn get-redacted-anthropic-api-key [state]
  (get-in state redacted-anthropic-api-key-path nil))

(defn has-anthropic-api-key? [state]
  (some? (get-redacted-api-key state :anthropic)))



