(ns gremllm.test-setup
  "Activates Malli function-schema instrumentation before test namespaces load.
   Loaded as a :devtools preload for the node-test builds.

   The explicit :require is load-bearing: (malli-dev/start!) collects
   :malli/schema metadata via (ana-api/all-ns) at COMPILE time, so any
   schema-bearing namespace not yet analyzed is silently skipped. Requiring the
   three annotated namespaces forces them into the graph first."
  (:require [gremllm.schema]
            [gremllm.renderer.actions.messages]
            [gremllm.renderer.actions.excerpt]
            [malli.core :as m]
            [malli.dev.cljs :as malli-dev]))

(malli-dev/start!)

;; Guard against the silent-no-op failure mode: if collection registered
;; nothing, fail loudly at load instead of passing a green-but-unchecked suite.
;; function-schemas is keyed by namespace, so count the fns across all of them.
;; The count tracks the three required namespaces above — a :malli/schema added
;; in a namespace not required here is silently uncovered, so add new annotation
;; sites to the :require list.
(let [instrumented (reduce + (map count (vals (m/function-schemas :cljs))))]
  (assert (>= instrumented 5)
          (str "Malli instrumentation collected " instrumented
               " fns (<5) — schema namespaces not analyzed before start!")))
