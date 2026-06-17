(ns gremllm.test-setup
  "Activates Malli function-schema instrumentation before test namespaces load.
   Loaded as a :devtools preload for the node-test builds.

   The explicit :require and :ns list are load-bearing: Malli collects
   :malli/schema metadata at compile time, so schema-bearing namespaces must be
   analyzed before instrumentation starts."
  (:require [gremllm.schema]
            [gremllm.renderer.actions.messages]
            [gremllm.renderer.actions.excerpt]
            [malli.core :as m]
            [malli.dev.cljs :as malli-dev]))

(def ^:private instrumented-namespaces
  '#{gremllm.schema
     gremllm.renderer.actions.messages
     gremllm.renderer.actions.excerpt})

(malli-dev/start! {:ns [gremllm.schema
                        gremllm.renderer.actions.messages
                        gremllm.renderer.actions.excerpt]})

;; Guard against the silent-no-op failure mode: if collection registered
;; nothing for a scoped namespace, fail loudly instead of passing a
;; green-but-unchecked suite.
(let [schemas (m/function-schemas :cljs)
      missing (remove #(seq (get schemas %)) instrumented-namespaces)]
  (assert (empty? missing)
          (str "Malli instrumentation missing schemas for "
               (pr-str (vec missing)))))
