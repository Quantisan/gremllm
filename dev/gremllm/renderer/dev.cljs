(ns gremllm.renderer.dev
  "Development entry point for renderer. Adds dev tooling then delegates to core."
  (:require [gremllm.renderer.core :as core]
            [nexus.action-log :as action-log]
            [dataspex.core :as dataspex]))

(defn ^:export main []
  (action-log/inspect)
  (dataspex/connect-remote-inspector)
  (core/main))
