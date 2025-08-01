(ns gremllm.main.dev
  (:require [gremllm.main.core :as core]
            [gremllm.main.integration-test :as integration-test]))

(defn main []
  ;; Development-specific setup
  (println "[INFO] Running in development mode.")

  ;; Enable electron-reload for hot reloading
  (let [electron-reload (js/require "electron-reload")]
    (electron-reload (.cwd js/process) #js {:ignored #"node_modules|[/\\]\.|target"}))

  ;; Load .env file
  (.config (js/require "@dotenvx/dotenvx") #js {:override true})

  (integration-test/test-safe-storage)

  ;; Delegate to the main entry point
  (core/main))
