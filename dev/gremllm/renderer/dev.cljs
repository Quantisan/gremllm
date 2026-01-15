(ns gremllm.renderer.dev
  "Development entry point for renderer. Adds dev tooling then delegates to core."
  (:require [gremllm.renderer.core :as core]
            [nexus.action-log :as action-log]
            [dataspex.core :as dataspex]))

(defn ^:export main []
  (action-log/inspect)
  ;; connect to our JVM Dataspex server for remote viewing at http://localhost:7117/
  (dataspex/connect-remote-inspector)
  (let [store (core/create-store)]
    (dataspex/inspect "Renderer state" store)
    (core/main store)))
