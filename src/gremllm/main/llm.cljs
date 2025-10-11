(ns gremllm.main.llm
  "LLM domain utilities - provider and model mappings"
  (:require [clojure.string :as str]))

(defn model->provider
  "Infers provider from model string. Pure function for easy testing."
  [model]
  (cond
    (str/starts-with? model "claude-") :anthropic
    (str/starts-with? model "gpt-")    :openai
    (str/starts-with? model "gemini-") :google
    :else (throw (js/Error. (str "Unknown provider for model: " model)))))

(defn provider->api-key-keyword
  "Maps provider to safeStorage lookup key. Pure function for easy testing."
  [provider]
  (case provider
    :anthropic :anthropic-api-key
    :openai    :openai-api-key
    :google    :gemini-api-key))

(defn provider->env-var-name
  "Maps provider to environment variable name. Pure function for easy testing."
  [provider]
  (case provider
    :anthropic "ANTHROPIC_API_KEY"
    :openai    "OPENAI_API_KEY"
    :google    "GEMINI_API_KEY"))
