(ns gremllm.renderer.actions.settings)

;; Stub actions for testing UI interactions

(defn update-api-key-input [_state value]
  (println "Settings: Update API key input:" value)
  []) ; Return empty effects for now

(defn save-api-key [_state]
  (println "Settings: Save API key clicked!")
  []) ; Return empty effects for now

(defn remove-api-key [_state]
  (println "Settings: Remove API key clicked!")
  []) ; Return empty effects for now
