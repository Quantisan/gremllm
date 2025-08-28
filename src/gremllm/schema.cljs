(ns gremllm.schema
  (:require [malli.core :as m]))

(defn generate-topic-id []
  ;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
  ;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

(def Message
  [:map
   [:type [:enum :user :assistant]]
   [:role [:enum "user" "assistant"]]
   [:content :string]])

(def Topic
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"} :string]
   [:messages {:default []} [:vector Message]]])

;; Coercion helpers for boundaries
(def validate-topic (partial m/coerce Topic))
