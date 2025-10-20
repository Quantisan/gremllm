(ns gremllm.main.llm
  "LLM provider utilities - API key and environment variable mappings")

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
