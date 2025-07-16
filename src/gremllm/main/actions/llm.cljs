(ns gremllm.main.actions.llm)

(defn query-llm-provider
  [messages api-key]
  (let [request-body {:model "claude-3-5-haiku-latest"
                      :max_tokens 8192
                      :messages messages}
        headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}]
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(if (.-ok %) (.json %) (throw (js/Error. "API request failed"))))
        (.then #(js->clj % :keywordize-keys true)))))

