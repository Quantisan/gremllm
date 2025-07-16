(ns gremllm.main.utils)

(defn nxr-result 
  "Extracts return value from Nexus dispatch result.
   Non-idiomatic hack for Electron IPC - see setup-api-handlers."
  [dispatch-result]
  (:res (first (:results dispatch-result))))
