(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations")

(defn query-llm-provider
  "Performs HTTP request to Anthropic API"
  [messages api-key]
  (-> (js/fetch "https://api.anthropic.com/v1/messages"
        #js {:method "POST"
             :headers #js {"x-api-key" api-key
                           "anthropic-version" "2023-06-01"
                           "content-type" "application/json"}
             :body (js/JSON.stringify
                    #js {:model "claude-3-5-sonnet-20241022"
                         :max_tokens 1024
                         :messages messages})})
      (.then #(if (.-ok %)
                (.json %)
                (throw (js/Error. "API request failed"))))
      (.then #(js->clj % :keywordize-keys true))))