(ns gremllm.renderer.dev
  "Development entry point for renderer. Adds dev tooling then delegates to core."
  (:require [gremllm.renderer.core :as core]
            [malli.dev.cljs :as malli-dev]
            [nexus.action-log :as action-log]
            [dataspex.core :as dataspex]))

(defn ^:export main []
  ;; Enforce :malli/schema hints on creation-point fns while developing, so the
  ;; shapes documented at those fns can't silently drift. Renderer-only: this
  ;; checker only wraps fns that execute in this (browser) runtime.
  (malli-dev/start!)
  (action-log/inspect)
  ;; connect to our JVM Dataspex server for remote viewing at http://localhost:7117/
  (dataspex/connect-remote-inspector)
  (let [store (core/create-store)]
    (dataspex/inspect "Renderer state" store)
    (core/main store)))
