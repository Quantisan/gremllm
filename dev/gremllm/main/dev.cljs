(ns gremllm.main.dev
  (:require [gremllm.main.core :as core]
            [gremllm.main.electron-runtime-test :as electron-runtime-test]))

;; NOTE: Dataspex/action-log not used here. dataspex.core uses js/location at
;; load time, which doesn't exist in Node.js (main process). Works in renderer
;; (browser context) but fails here. Requires upstream fix to make init lazy.

(defn main []
  (println "[INFO] Running in development mode.")

  ;; Enable electron-reload for hot reloading
  (let [electron-reload (js/require "electron-reload")]
    (electron-reload (.cwd js/process) #js {:ignored #"node_modules|[/\\]\.|target"}))

  ;; Load .env file
  (.config (js/require "@dotenvx/dotenvx") #js {:override true})

  (electron-runtime-test/test-safe-storage)

  ;; Delegate to the main entry point
  (core/main))
