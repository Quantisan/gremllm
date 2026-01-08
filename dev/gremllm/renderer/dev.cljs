(ns gremllm.renderer.dev
  "Browser-only development tooling. Required by renderer/core for side effects."
  (:require [nexus.action-log :as action-log]
            [dataspex.core :as dataspex]))

(action-log/inspect)
(dataspex/connect-remote-inspector)
