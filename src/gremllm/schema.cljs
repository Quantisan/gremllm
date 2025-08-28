(ns gremllm.schema
  (:require [malli.core :as m]))

(def Message
  [:map
   [:type [:enum :user :assistant]]
   [:role [:enum "user" "assistant"]]
   [:content :string]])

(def Topic
  [:map
   [:id :string]
   [:name :string]
   [:messages [:vector Message]]])

;; Coercion helpers for boundaries
(def validate-topic (partial m/coerce Topic))
