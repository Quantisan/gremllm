(ns gremllm.main.state
  "State paths and accessors for the main process store")

;; Path constants
(def workspace-dir-path [:workspace-dir])

;; State accessor functions
(defn get-workspace-dir [state]
  (get-in state workspace-dir-path))
