(ns gremllm.schema
  (:require [malli.core :as m]
            [malli.transform :as mt]))

(defn generate-topic-id []
  ;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
  ;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

(def Message
  [:map
   [:id :int]
   [:type [:enum :user :assistant]]
   [:text :string]])

(def Topic
  [:map
   [:id {:default/fn generate-topic-id} :string]
   [:name {:default "New Topic"} :string]
   [:messages {:default []} [:vector Message]]
   [:unsaved? {:optional true :default false} :boolean]])

;; Coercion helpers for boundaries
(def decode-topic (m/coercer Topic mt/json-transformer))
